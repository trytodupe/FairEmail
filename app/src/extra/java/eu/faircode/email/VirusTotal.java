package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2022 by Marcel Bokhorst (M66B)
*/

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Pair;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URL;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.HttpsURLConnection;

public class VirusTotal {
    static final String URI_ENDPOINT = "https://www.virustotal.com/";
    static final String URI_PRIVACY = "https://support.virustotal.com/hc/en-us/articles/115002168385-Privacy-Policy";

    private static final int VT_TIMEOUT = 20; // seconds
    private static final long VT_ANALYSIS_WAIT = 6000L; // milliseconds
    private static final int VT_ANALYSIS_CHECKS = 50; // 50 x 6 sec = 5 minutes

    static Bundle lookup(Context context, File file, String apiKey) throws NoSuchAlgorithmException, IOException, JSONException {
        String hash;
        try (InputStream is = new FileInputStream(file)) {
            hash = Helper.getHash(is, "SHA-256");
        }

        String uri = URI_ENDPOINT + "gui/file/" + hash;
        Log.i("VT uri=" + uri);

        Bundle result = new Bundle();
        result.putString("uri", uri);

        if (TextUtils.isEmpty(apiKey))
            return result;

        Pair<Integer, String> response = call(context, "api/v3/files/" + hash, apiKey);
        if (response.first != HttpsURLConnection.HTTP_OK &&
                response.first != HttpsURLConnection.HTTP_NOT_FOUND)
            throw new FileNotFoundException(response.second);

        if (response.first == HttpsURLConnection.HTTP_NOT_FOUND) {
            result.putInt("count", 0);
            result.putInt("malicious", 0);
        } else {
            // https://developers.virustotal.com/reference/files
            // Example: https://gist.github.com/M66B/4ea95fdb93fb10bf4047761fcc9ec21a
            JSONObject jroot = new JSONObject(response.second);
            JSONObject jdata = jroot.getJSONObject("data");
            JSONObject jattributes = jdata.getJSONObject("attributes");

            JSONObject jclassification = jattributes.optJSONObject("popular_threat_classification");
            String label = (jclassification == null ? null : jclassification.getString("suggested_threat_label"));

            int count = 0;
            int malicious = 0;
            JSONObject janalysis = jattributes.getJSONObject("last_analysis_results");
            JSONArray jnames = janalysis.names();
            for (int i = 0; i < jnames.length(); i++) {
                String name = jnames.getString(i);
                JSONObject jresult = janalysis.getJSONObject(name);
                String category = jresult.getString("category");
                Log.i("VT " + name + "=" + category);
                if (!"type-unsupported".equals(category))
                    count++;
                if ("malicious".equals(category))
                    malicious++;
            }

            Log.i("VT analysis=" + malicious + "/" + count + " label=" + label);

            result.putInt("count", count);
            result.putInt("malicious", malicious);
            result.putString("label", label);
        }

        return result;
    }

    static void upload(Context context, File file, String apiKey) throws IOException, JSONException, InterruptedException, TimeoutException {
        // Get upload URL
        Pair<Integer, String> response = call(context, "api/v3/files/upload_url", apiKey);
        if (response.first != HttpsURLConnection.HTTP_OK)
            throw new FileNotFoundException(response.second);
        JSONObject jurl = new JSONObject(response.second);
        String upload_url = jurl.getString("data");

        // Upload file
        String id;
        String boundary = "----FairEmail." + System.currentTimeMillis();

        URL url = new URL(upload_url);
        Log.i("VT upload url=" + url);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setReadTimeout(VT_TIMEOUT * 1000);
        connection.setConnectTimeout(VT_TIMEOUT * 1000);
        ConnectionHelper.setUserAgent(context, connection);
        connection.setRequestProperty("x-apikey", apiKey);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.connect();

        try {
            OutputStream os = connection.getOutputStream();

            PrintWriter writer = new PrintWriter(new OutputStreamWriter(os));
            writer
                    .append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data;")
                    .append(" name=\"file\";")
                    .append(" filename=\"").append(file.getName()).append("\"").append("\r\n")
                    .append("Content-Type: application/octet-stream").append("\r\n")
                    .append("Content-Transfer-Encoding: binary").append("\r\n")
                    .append("\r\n")
                    .flush();

            try (InputStream is = new FileInputStream(file)) {
                Helper.copy(is, os);
            }

            os.flush();

            writer
                    .append("\r\n")
                    .append("--").append(boundary).append("--").append("\r\n")
                    .flush();

            writer.close();

            int status = connection.getResponseCode();
            if (status != HttpsURLConnection.HTTP_OK) {
                String error = "Error " + status + ": " + connection.getResponseMessage();
                try {
                    InputStream is = connection.getErrorStream();
                    if (is != null)
                        error += "\n" + Helper.readStream(is);
                } catch (Throwable ex) {
                    Log.w(ex);
                }
                Log.w("VT " + error);
                throw new FileNotFoundException(error);
            }

            String r = Helper.readStream(connection.getInputStream());
            Log.i("VT response=" + r);
            JSONObject jfile = new JSONObject(r);
            JSONObject jdata = jfile.getJSONObject("data");
            id = jdata.getString("id");

        } finally {
            connection.disconnect();
        }

        // Get analysis result
        for (int i = 0; i < VT_ANALYSIS_CHECKS; i++) {
            Pair<Integer, String> analyses = call(context, "api/v3/analyses/" + id, apiKey);
            if (analyses.first != HttpsURLConnection.HTTP_OK)
                throw new FileNotFoundException(analyses.second);

            JSONObject janalysis = new JSONObject(analyses.second);
            JSONObject jdata = janalysis.getJSONObject("data");
            JSONObject jattributes = jdata.getJSONObject("attributes");
            String status = jattributes.getString("status");
            Log.i("VT status=" + status);

            if (!"queued".equals(status))
                return;

            Thread.sleep(VT_ANALYSIS_WAIT);
        }

        throw new TimeoutException("Analysis");
    }

    static Pair<Integer, String> call(Context context, String api, String apiKey) throws IOException {
        URL url = new URL(URI_ENDPOINT + api);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setReadTimeout(VT_TIMEOUT * 1000);
        connection.setConnectTimeout(VT_TIMEOUT * 1000);
        ConnectionHelper.setUserAgent(context, connection);
        connection.setRequestProperty("x-apikey", apiKey);
        connection.setRequestProperty("Accept", "application/json");
        connection.connect();

        try {
            int status = connection.getResponseCode();
            if (status != HttpsURLConnection.HTTP_OK) {
                String error = "Error " + status + ": " + connection.getResponseMessage();
                try {
                    InputStream is = connection.getErrorStream();
                    if (is != null)
                        error += "\n" + Helper.readStream(is);
                } catch (Throwable ex) {
                    Log.w(ex);
                }
                return new Pair<>(status, error);
            }

            String response = Helper.readStream(connection.getInputStream());
            //Log.i("VT response=" + response);
            return new Pair<>(status, response);

        } finally {
            connection.disconnect();
        }
    }
}
