package eu.faircode.email;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.provider.Settings;
import android.telephony.TelephonyManager;

import androidx.preference.PreferenceManager;

import com.bugsnag.android.BreadcrumbType;
import com.bugsnag.android.Bugsnag;
import com.sun.mail.imap.IMAPStore;
import com.sun.mail.smtp.SMTPTransport;

import org.xbill.DNS.Lookup;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

import java.lang.reflect.Field;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.mail.Address;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;

public class ConnectionHelper {
    private static final String any4 = "0.0.0.0";
    // https://dns.watch/
    private static final String DEFAULT_DNS = "84.200.69.80";

    static final int AUTH_TYPE_PASSWORD = 1;
    static final int AUTH_TYPE_GMAIL = 2;

    // Roam like at home
    // https://en.wikipedia.org/wiki/European_Union_roaming_regulations
    private static final List<String> RLAH_COUNTRY_CODES = Collections.unmodifiableList(Arrays.asList(
            "AT", // Austria
            "BE", // Belgium
            "BG", // Bulgaria
            "HR", // Croatia
            "CY", // Cyprus
            "CZ", // Czech Republic
            "DK", // Denmark
            "EE", // Estonia
            "FI", // Finland
            "FR", // France
            "DE", // Germany
            "GR", // Greece
            "HU", // Hungary
            "IS", // Iceland
            "IE", // Ireland
            "IT", // Italy
            "LV", // Latvia
            "LI", // Liechtenstein
            "LT", // Lithuania
            "LU", // Luxembourg
            "MT", // Malta
            "NL", // Netherlands
            "NO", // Norway
            "PL", // Poland
            "PT", // Portugal
            "RO", // Romania
            "SK", // Slovakia
            "SI", // Slovenia
            "ES", // Spain
            "SE", // Sweden
            "GB" // United Kingdom
    ));

    static class NetworkState {
        private Boolean connected = null;
        private Boolean suitable = null;
        private Boolean unmetered = null;
        private Boolean roaming = null;

        boolean isConnected() {
            return (connected != null && connected);
        }

        boolean isSuitable() {
            return (suitable != null && suitable);
        }

        boolean isUnmetered() {
            return (unmetered != null && unmetered);
        }

        boolean isRoaming() {
            return (roaming != null && roaming);
        }

        public void update(NetworkState newState) {
            connected = newState.connected;
            unmetered = newState.unmetered;
            suitable = newState.suitable;
        }
    }

    static NetworkState getNetworkState(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean metered = prefs.getBoolean("metered", true);
        boolean roaming = prefs.getBoolean("roaming", true);
        boolean rlah = prefs.getBoolean("rlah", true);

        NetworkState state = new NetworkState();
        try {
            Boolean isMetered = isMetered(context);
            state.connected = (isMetered != null);
            state.unmetered = (isMetered != null && !isMetered);
            state.suitable = (isMetered != null && (metered || !isMetered));

            if (state.connected && !roaming) {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    NetworkInfo ani = (cm == null ? null : cm.getActiveNetworkInfo());
                    if (ani != null)
                        state.roaming = ani.isRoaming();
                } else {
                    Network active = (cm == null ? null : cm.getActiveNetwork());
                    if (active != null) {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                        if (caps != null)
                            state.roaming = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
                    }
                }

                if (state.roaming != null && state.roaming && rlah)
                    try {
                        TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                        if (tm != null) {
                            String sim = tm.getSimCountryIso();
                            String network = tm.getNetworkCountryIso();
                            Log.i("Country SIM=" + sim + " network=" + network);
                            if (sim != null && network != null &&
                                    RLAH_COUNTRY_CODES.contains(sim) &&
                                    RLAH_COUNTRY_CODES.contains(network))
                                state.roaming = false;
                        }
                    } catch (Throwable ex) {
                        Log.w(ex);
                    }
            }
        } catch (Throwable ex) {
            Log.e(ex);
        }

        return state;
    }

    private static Boolean isMetered(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            NetworkInfo ani = (cm == null ? null : cm.getActiveNetworkInfo());
            if (ani == null || !ani.isConnected())
                return null;
            return cm.isActiveNetworkMetered();
        }

        Network active = (cm == null ? null : cm.getActiveNetwork());
        if (active == null) {
            Log.i("isMetered: no active network");
            return null;
        }

        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
        if (caps == null) {
            Log.i("isMetered: active no caps");
            return null; // network unknown
        }

        Log.i("isMetered: active caps=" + caps);

        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            Log.i("isMetered: no internet");
            return null;
        }

        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)) {
            Log.i("isMetered: active restricted");
            return null;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)) {
            Log.i("isMetered: active background");
            return null;
        }

        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
            boolean unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            Log.i("isMetered: active not VPN unmetered=" + unmetered);
            return !unmetered;
        }

        // VPN: evaluate underlying networks

        boolean underlying = false;
        Network[] networks = cm.getAllNetworks();
        for (Network network : networks) {
            caps = cm.getNetworkCapabilities(network);
            if (caps == null) {
                Log.i("isMetered: no underlying caps");
                continue; // network unknown
            }

            Log.i("isMetered: underlying caps=" + caps);

            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                Log.i("isMetered: underlying no internet");
                continue;
            }

            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)) {
                Log.i("isMetered: underlying restricted");
                continue;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)) {
                Log.i("isMetered: underlying background");
                continue;
            }

            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                underlying = true;
                Log.i("isMetered: underlying is connected");

                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                    Log.i("isMetered: underlying is unmetered");
                    return false;
                }
            }
        }

        if (!underlying) {
            Log.i("isMetered: no underlying network");
            return null;
        }

        // Assume metered
        Log.i("isMetered: underlying assume metered");
        return true;
    }

    static void connect(Context context, IMAPStore istore, EntityAccount account) throws MessagingException {
        connect(context, istore, account.host, account.port, account.user, account.password);
    }

    static void connect(Context context, SMTPTransport itransport, EntityIdentity identity) throws MessagingException {
        connect(context, itransport, identity.host, identity.port, identity.user, identity.password);
    }

    static void connect(Context context, IMAPStore istore, String host, int port, String user, String password) throws MessagingException {
        try {
            istore.connect(host, port, user, password);
        } catch (MessagingException ex) {
            if (!hasIPv6(host))
                throw ex;

            try {
                Log.i("Binding to " + any4);
                Field fSession = getDeclaredField(istore.getClass(), "session");
                fSession.setAccessible(true);
                Session isession = (Session) fSession.get(istore);
                isession.getProperties().put("mail.imap.localaddress", any4);
                isession.getProperties().put("mail.imaps.localaddress", any4);
                istore.connect(host, port, user, password);
            } catch (Throwable ex1) {
                Log.w(ex1);
                throw ex;
            }
        }

        // https://www.ietf.org/rfc/rfc2971.txt
        if (istore.hasCapability("ID"))
            try {
                Map<String, String> id = new LinkedHashMap<>();
                id.put("name", context.getString(R.string.app_name));
                id.put("version", BuildConfig.VERSION_NAME);
                Map<String, String> sid = istore.id(id);
                if (sid != null) {
                    Map<String, String> crumb = new HashMap<>();
                    for (String key : sid.keySet()) {
                        crumb.put(key, sid.get(key));
                        EntityLog.log(context, "Server " + key + "=" + sid.get(key));
                    }
                    Bugsnag.leaveBreadcrumb("server", BreadcrumbType.LOG, crumb);
                }
            } catch (MessagingException ex) {
                Log.w(ex);
            }
    }

    static void connect(Context context, SMTPTransport itransport, String host, int port, String user, String password) throws MessagingException {
        try {
            itransport.connect(host, port, user, password);
        } catch (MessagingException ex) {
            if (!hasIPv6(host))
                throw ex;

            try {
                Log.i("Binding to " + any4);
                Field fSession = getDeclaredField(itransport.getClass(), "session");
                fSession.setAccessible(true);
                Session isession = (Session) fSession.get(itransport);
                isession.getProperties().put("mail.smtp.localaddress", any4);
                isession.getProperties().put("mail.smtps.localaddress", any4);
                itransport.connect(host, port, user, password);
            } catch (Throwable ex1) {
                Log.w(ex1);
                throw ex;
            }
        }
    }

    private static Field getDeclaredField(Class clazz, String name) throws NoSuchFieldException {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException ex) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static boolean hasIPv6(String host) {
        boolean has = false;
        try {
            for (InetAddress iaddr : InetAddress.getAllByName(host)) {
                Log.i(host + " resolves to " + iaddr);
                if (iaddr instanceof Inet6Address)
                    has = true;
            }
        } catch (UnknownHostException ignored) {
        }
        return has;
    }

    static boolean airplaneMode(Context context) {
        return Settings.System.getInt(context.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
    }

    static String getDnsServer(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null)
            return DEFAULT_DNS;

        LinkProperties props = null;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
            for (Network network : cm.getAllNetworks()) {
                NetworkInfo ni = cm.getNetworkInfo(network);
                if (ni != null && ni.isConnected()) {
                    props = cm.getLinkProperties(network);
                    Log.i("Old props=" + props);
                    break;
                }
            }
        else {
            Network active = cm.getActiveNetwork();
            if (active == null)
                return DEFAULT_DNS;
            props = cm.getLinkProperties(active);
            Log.i("New props=" + props);
        }

        if (props == null)
            return DEFAULT_DNS;

        List<InetAddress> dns = props.getDnsServers();
        if (dns.size() == 0)
            return DEFAULT_DNS;
        else
            return dns.get(0).getHostAddress();
    }

    static boolean lookupMx(Address[] addresses, Context context) throws UnknownHostException {
        boolean ok = true;

        if (addresses != null)
            for (Address address : addresses)
                try {
                    String email = ((InternetAddress) address).getAddress();
                    if (email == null || !email.contains("@"))
                        continue;
                    String domain = email.split("@")[1];
                    Lookup lookup = new Lookup(domain, Type.MX);
                    SimpleResolver resolver = new SimpleResolver(ConnectionHelper.getDnsServer(context));
                    lookup.setResolver(resolver);
                    Log.i("Lookup MX=" + domain + " @" + resolver.getAddress());

                    lookup.run();
                    if (lookup.getResult() == Lookup.HOST_NOT_FOUND ||
                            lookup.getResult() == Lookup.TYPE_NOT_FOUND) {
                        Log.i("Lookup MX=" + domain + " result=" + lookup.getErrorString());
                        throw new UnknownHostException(context.getString(R.string.title_no_server, domain));
                    } else if (lookup.getResult() != Lookup.SUCCESSFUL)
                        ok = false;
                } catch (UnknownHostException ex) {
                    throw ex;
                } catch (Throwable ex) {
                    Log.e(ex);
                    ok = false;
                }

        return ok;
    }
}
