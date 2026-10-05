import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.security.MessageDigest;

public class CrimeLensAI {

    static final int PORT = 8080;

    static final String USER = "crimeadmin";
    static final String PASS = "crime@2026";
    static final String CODE = "cl2026";

    static final Path DIR = Paths.get(
            System.getProperty("user.home"),
            "Desktop",
            "CrimeLensAI_Data"
    );

    static final Path CASES = DIR.resolve("cases.csv");
    static final Path EVID = DIR.resolve("evidence.csv");
    static final Path INV = DIR.resolve("investigators.csv");
    static final Path TIME = DIR.resolve("timeline.csv");
    static final Path AUDIT = DIR.resolve("audit.log");
    static final Path CUSTODY = DIR.resolve("custody.csv");
    static final Path MEDIA_DIR = DIR.resolve("evidence_media");

    static final Map<String, Long> sessions =
            new ConcurrentHashMap<>();

    static final Map<String, String> sessionRoles =
            new ConcurrentHashMap<>();

    static final ThreadLocal<String> currentRole = new ThreadLocal<>();

    static final DateTimeFormatter F =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static final List<C> cs =
            new CopyOnWriteArrayList<>();

    static final List<E> es =
            new CopyOnWriteArrayList<>();

    static final List<I> is =
            new CopyOnWriteArrayList<>();

    static final List<T> ts =
            new CopyOnWriteArrayList<>();

    static class C {
        String id, title, cat, loc, investigator, suspect;
        String priority, status, desc, date;
        int evidence;

        C(String... x) {
            id = x[0];
            title = x[1];
            cat = x[2];
            loc = x[3];
            investigator = x[4];
            suspect = x[5];
            priority = x[6];
            status = x[7];
            evidence = Integer.parseInt(x[8]);
            desc = x[9];
            date = x[10];
        }
    }

    static class E {
        String id, caseId, type, cat, desc, loc;
        String by, inv, condition, date, hash, fileName, fileType, filePath, fileHash;

        E(String... x) {
            id = x[0];
            caseId = x[1];
            type = x[2];
            cat = x[3];
            desc = x[4];
            loc = x[5];
            by = x[6];
            inv = x[7];
            condition = x[8];
            date = x[9];
            hash = x.length > 10 ? x[10] : sha256(row(id, caseId, type, cat, desc, loc, by, inv, condition, date));
            fileName = x.length > 11 ? x[11] : "";
            fileType = x.length > 12 ? x[12] : "";
            filePath = x.length > 13 ? x[13] : "";
            fileHash = x.length > 14 ? x[14] : "";
        }
    }

    static class I {
        String id, name, role, dept, spec, contact, date;

        I(String... x) {
            id = x[0];
            name = x[1];
            role = x[2];
            dept = x[3];
            spec = x[4];
            contact = x[5];
            date = x[6];
        }
    }

    static class T {
        String id, caseId, event, desc, date;

        T(String... x) {
            id = x[0];
            caseId = x[1];
            event = x[2];
            desc = x[3];
            date = x[4];
        }
    }

    static class Custody {
        String id, evidenceId, action, fromUser, toUser, location, note, date;
        Custody(String... x) {
            id=x[0]; evidenceId=x[1]; action=x[2]; fromUser=x[3];
            toUser=x[4]; location=x[5]; note=x[6]; date=x[7];
        }
    }

    static final List<Custody> custody = new CopyOnWriteArrayList<>();

    public static void main(String[] a) throws Exception {

        init();

        HttpServer s =
                HttpServer.create(
                        new InetSocketAddress(
                                "127.0.0.1",
                                PORT
                        ),
                        0
                );

        s.createContext("/", CrimeLensAI::home);
        s.createContext("/login", CrimeLensAI::login);
        s.createContext("/logout", CrimeLensAI::logout);

        s.createContext("/dashboard",
                CrimeLensAI::dash);

        s.createContext("/add-case",
                CrimeLensAI::addCase);

        s.createContext("/cases",
                CrimeLensAI::cases);

        s.createContext("/evidence",
                CrimeLensAI::evidence);

        s.createContext("/evidence-file",
                CrimeLensAI::evidenceFile);

        s.createContext("/custody",
                CrimeLensAI::custody);

        s.createContext("/verify",
                CrimeLensAI::verify);

        s.createContext("/alerts",
                CrimeLensAI::alerts);

        s.createContext("/report",
                CrimeLensAI::report);

        s.createContext("/investigators",
                CrimeLensAI::investigators);

        s.createContext("/timeline",
                CrimeLensAI::timeline);

        s.createContext("/insight",
                CrimeLensAI::insight);

        s.createContext("/search",
                CrimeLensAI::search);

        s.createContext("/export",
                CrimeLensAI::export);

        s.createContext("/audit",
                CrimeLensAI::audit);

        s.setExecutor(
                Executors.newCachedThreadPool()
        );

        s.start();

        System.out.println();
        System.out.println("======================================");
        System.out.println("          CRIMELENS AI");
        System.out.println("======================================");
        System.out.println(
                "Open: http://localhost:" + PORT
        );
        System.out.println(
                "Data: " + DIR
        );
        System.out.println("======================================");
    }

    static void init() throws IOException {

        Files.createDirectories(DIR);
        Files.createDirectories(MEDIA_DIR);

        file(
                CASES,
                "id,title,category,location,investigator,"
                        + "suspect,priority,status,evidence,"
                        + "description,date"
        );

        file(
                EVID,
                "id,caseId,type,category,description,"
                        + "location,collectedBy,investigatorId,"
                        + "condition,date,sha256,fileName,fileType,filePath,fileSha256"
        );

        file(
                INV,
                "id,name,role,department,"
                        + "specialization,contact,date"
        );

        file(
                TIME,
                "id,caseId,event,description,date"
        );

        file(
                CUSTODY,
                "id,evidenceId,action,fromUser,toUser,location,note,date"
        );

        if (!Files.exists(AUDIT)) {
            Files.createFile(AUDIT);
        }

        load();
    }

    static void file(Path p, String h)
            throws IOException {

        if (!Files.exists(p)) {
            Files.writeString(
                    p,
                    h + "\n"
            );
        }
    }

    static void load() {

        try {
            List<String> lines =
                    Files.readAllLines(CASES);

            for (int i = 1; i < lines.size(); i++) {

                String[] x =
                        csv(lines.get(i));

                if (x.length >= 11) {
                    cs.add(new C(x));
                }
            }

        } catch (Exception ignored) {
        }

        try {
            List<String> lines =
                    Files.readAllLines(EVID);

            for (int i = 1; i < lines.size(); i++) {

                String[] x =
                        csv(lines.get(i));

                if (x.length >= 10) {
                    es.add(new E(x));
                }
            }

        } catch (Exception ignored) {
        }

        try {
            List<String> lines =
                    Files.readAllLines(INV);

            for (int i = 1; i < lines.size(); i++) {

                String[] x =
                        csv(lines.get(i));

                if (x.length >= 7) {
                    is.add(new I(x));
                }
            }

        } catch (Exception ignored) {
        }

        try {
            List<String> lines =
                    Files.readAllLines(TIME);

            for (int i = 1; i < lines.size(); i++) {

                String[] x =
                        csv(lines.get(i));

                if (x.length >= 5) {
                    ts.add(new T(x));
                }
            }

        } catch (Exception ignored) {
        }

        try {
            List<String> lines = Files.readAllLines(CUSTODY);
            for (int i = 1; i < lines.size(); i++) {
                String[] x = csv(lines.get(i));
                if (x.length >= 8) custody.add(new Custody(x));
            }
        } catch (Exception ignored) {
        }
    }

    static String[] csv(String s) {

        List<String> r =
                new ArrayList<>();

        StringBuilder b =
                new StringBuilder();

        boolean q = false;

        for (char c : s.toCharArray()) {

            if (c == '"') {
                q = !q;
            } else if (c == ',' && !q) {

                r.add(b.toString());
                b.setLength(0);

            } else {
                b.append(c);
            }
        }

        r.add(b.toString());

        return r.toArray(new String[0]);
    }

    static String clean(String s) {

        return s == null
                ? ""
                : s.replace("\r", " ")
                   .replace("\n", " ")
                   .replace(",", ";")
                   .trim();
    }

    static String row(String... x) {

        StringBuilder b =
                new StringBuilder();

        for (int i = 0; i < x.length; i++) {

            if (i > 0) {
                b.append(',');
            }

            String v = clean(x[i]);

            b.append(v);
        }

        return b.toString();
    }

    static void add(
            Path p,
            String... x) {

        try {

            Files.writeString(
                    p,
                    row(x) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );

        } catch (Exception ignored) {
        }
    }

    static String esc(String s) {

        if (s == null) {
            return "";
        }

        return s
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    static Map<String, String> form(
            HttpExchange e)
            throws IOException {

        String q =
                new String(
                        e.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8
                );

        Map<String, String> m =
                new HashMap<>();

        for (String p : q.split("&")) {

            if (p.isEmpty()) {
                continue;
            }

            String[] z =
                    p.split("=", 2);

            m.put(
                    URLDecoder.decode(
                            z[0],
                            "UTF-8"
                    ),
                    z.length > 1
                            ? URLDecoder.decode(
                                    z[1],
                                    "UTF-8"
                            )
                            : ""
            );
        }

        return m;
    }

    static void send(
            HttpExchange e,
            String s)
            throws IOException {

        byte[] b =
                s.getBytes(
                        StandardCharsets.UTF_8
                );

        e.getResponseHeaders()
                .set(
                        "Content-Type",
                        "text/html; charset=UTF-8"
                );

        e.sendResponseHeaders(
                200,
                b.length
        );

        try (OutputStream o =
                     e.getResponseBody()) {

            o.write(b);
        }
    }

    static void red(
            HttpExchange e,
            String u)
            throws IOException {

        e.getResponseHeaders()
                .set("Location", u);

        e.sendResponseHeaders(
                302,
                -1
        );

        e.close();
    }

    static String sid(HttpExchange e) {

        String c =
                e.getRequestHeaders()
                        .getFirst("Cookie");

        if (c == null) {
            return null;
        }

        for (String p : c.split(";")) {

            if (p.trim().startsWith("SID=")) {

                return p.trim()
                        .substring(4);
            }
        }

        return null;
    }

    static boolean auth(
            HttpExchange e) {

        String s = sid(e);

        return s != null
                && sessions.containsKey(s)
                && System.currentTimeMillis()
                - sessions.get(s)
                < 1800000;
    }

    static String nav() {
        String r = currentRole.get();
        if (r == null) r = "Viewer";
        StringBuilder n = new StringBuilder();
        n.append("<aside><h2>CrimeLens <span>AI</span></h2>");
        n.append("<a href=\"/dashboard\">Overview</a>");
        if (isAdmin(r) || isInvestigator(r)) n.append("<a href=\"/add-case\">Register Case</a>");
        n.append("<a href=\"/cases\">Case Records</a>");
        if (isAdmin(r) || isInvestigator(r)) n.append("<a href=\"/evidence\">Evidence Management</a>");
        if (isAdmin(r) || isInvestigator(r)) n.append("<a href=\"/custody\">Chain of Custody</a>");
        if (isAdmin(r)) n.append("<a href=\"/investigators\">Investigator Management</a>");
        n.append("<a href=\"/timeline\">Case Timeline</a>");
        n.append("<a href=\"/insight\">AI Case Insight</a>");
        n.append("<a href=\"/search\">Smart Search 2.0</a>");
        if (isAdmin(r) || isInvestigator(r)) n.append("<a href=\"/verify\">Evidence Integrity</a>");
        n.append("<a href=\"/alerts\">Automatic Alerts</a>");
        n.append("<a href=\"/report\">Case Report Generator</a>");
        if (isAdmin(r) || isInvestigator(r)) n.append("<a href=\"/export\">Data Export</a>");
        if (isAdmin(r)) n.append("<a href=\"/audit\">Audit Trail</a>");
        n.append("<a href=\"/logout\">Logout</a></aside>");
        return n.toString();
    }

    static boolean isAdmin(String r) { return "Admin".equals(r); }
    static boolean isInvestigator(String r) { return "Investigator".equals(r); }
    static boolean isEditor(String r) { return isAdmin(r) || isInvestigator(r); }

    static String page(
            String title,
            String body) {

        return """
        <!doctype html>
        <html>
        <head>
        <meta charset="UTF-8">
        <meta name="viewport"
              content="width=device-width,initial-scale=1">

        <title>
        """ + esc(title) + """
        - CrimeLens AI
        </title>

        <style>
        """ + css() + """
        </style>

        </head>

        <body>
        """ + nav() + """

        <main>

        <header>

            <div>
                <small>
                    SMART FORENSIC RECORD MANAGEMENT
                </small>

                <h1>
                """ + esc(title) + """
                </h1>
            </div>

            <div class="live">
                SYSTEM ONLINE
            </div>

        </header>

        """ + body + """

        <footer>
            CrimeLens AI • Academic Prototype
            • Advanced Dashboard • Rule-based AI Insight
        </footer>

        </main>

        </body>
        </html>
        """;
    }

    static String css() {

        return """
        * {
            box-sizing: border-box;
        }

        body {
            margin: 0;
            background: #061321;
            color: #dceaf5;
            font-family: Arial, sans-serif;
            font-size: 14px;
        }

        aside {
            position: fixed;
            left: 0;
            top: 0;
            bottom: 0;
            width: 245px;
            background: #071b2e;
            border-right: 1px solid #173b55;
            padding: 25px 15px;
        }

        aside h2 {
            margin: 0 10px 28px;
            color: white;
            font-size: 22px;
        }

        aside h2 span {
            color: #29d8e5;
        }

        aside a {
            display: block;
            color: #8ea8ba;
            text-decoration: none;
            padding: 12px 14px;
            margin: 4px 0;
            border-radius: 8px;
        }

        aside a:hover {
            background: #10304a;
            color: #4ce0eb;
        }

        main {
            margin-left: 245px;
            min-height: 100vh;
        }

        header {
            height: 92px;
            background: #081a2b;
            border-bottom: 1px solid #17364e;
            padding: 20px 30px;
            display: flex;
            align-items: center;
            justify-content: space-between;
        }

        header small {
            color: #5e8199;
            letter-spacing: 2px;
            font-size: 9px;
        }

        h1 {
            margin: 5px 0 0;
            font-size: 25px;
        }

        .live {
            color: #55dfb0;
            font-size: 10px;
            border: 1px solid #235a4c;
            padding: 8px 12px;
            border-radius: 20px;
        }

        .wrap {
            padding: 28px 30px;
        }

        .cards {
            display: grid;
            grid-template-columns:
                repeat(
                    auto-fit,
                    minmax(170px, 1fr)
                );
            gap: 15px;
        }

        .card,
        .panel {
            background: #0a2034;
            border: 1px solid #183d57;
            border-radius: 12px;
            padding: 20px;
        }

        .card small {
            color: #66879d;
            text-transform: uppercase;
            font-size: 9px;
        }

        .card b {
            display: block;
            font-size: 28px;
            margin-top: 8px;
            color: #45dbe6;
        }

        .panel {
            margin-top: 18px;
        }

        .grid {
            display: grid;
            grid-template-columns:
                repeat(
                    auto-fit,
                    minmax(220px, 1fr)
                );
            gap: 14px;
        }

        .field {
            margin-bottom: 12px;
        }

        .field label {
            display: block;
            color: #89a5b8;
            font-size: 11px;
            margin-bottom: 6px;
        }

        input,
        select,
        textarea {
            width: 100%;
            background: #061724;
            color: #e5f3fb;
            border: 1px solid #21445d;
            border-radius: 7px;
            padding: 11px;
            outline: none;
        }

        textarea {
            min-height: 95px;
            resize: vertical;
        }

        .btn {
            display: inline-block;
            background: #16aebd;
            color: white;
            border: 0;
            border-radius: 7px;
            padding: 11px 17px;
            text-decoration: none;
            cursor: pointer;
        }

        .btn:hover {
            background: #1ac7d5;
        }

        .muted {
            color: #7894a8;
            line-height: 1.6;
        }

        .case,
        .item {
            background: #071a2b;
            border: 1px solid #193d56;
            border-radius: 11px;
            padding: 18px;
            margin: 12px 0;
        }

        .case h2,
        .item h3 {
            margin: 5px 0;
        }

        .tag {
            display: inline-block;
            background: #103650;
            color: #6bdbe5;
            border-radius: 20px;
            padding: 5px 9px;
            font-size: 10px;
            margin: 3px;
        }

        .critical {
            background: #41252d;
            color: #ff9ea8;
        }

        .row {
            display: flex;
            justify-content: space-between;
            gap: 15px;
            align-items: center;
        }

        .kv {
            display: grid;
            grid-template-columns:
                repeat(
                    auto-fit,
                    minmax(130px, 1fr)
                );
            gap: 9px;
            margin-top: 12px;
        }

        .kv div {
            background: #061724;
            border: 1px solid #17384f;
            padding: 10px;
            border-radius: 7px;
        }

        .kv small {
            display: block;
            color: #5f7e93;
            font-size: 9px;
        }

        .kv b {
            display: block;
            margin-top: 4px;
            font-size: 12px;
        }

        .timeline {
            border-left: 2px solid #18516a;
            padding-left: 20px;
        }

        .event {
            position: relative;
            margin: 0 0 20px;
        }

        .event:before {
            content: "";
            position: absolute;
            left: -27px;
            top: 4px;
            width: 10px;
            height: 10px;
            border-radius: 50%;
            background: #27d6e4;
            box-shadow:
                0 0 10px #27d6e4;
        }

        .ai {
            background:
                linear-gradient(
                    145deg,
                    #092c3d,
                    #071a29
                );
            border-color: #176276;
        }

        .ai h2 {
            color: #4ce0eb;
        }

        .insight {
            padding: 15px;
            background: #071a2a;
            border: 1px solid #19475c;
            border-radius: 9px;
            margin: 10px 0;
            line-height: 1.7;
            color: #b9cedb;
        }

        .empty {
            text-align: center;
            color: #638096;
            padding: 30px;
        }

        .log {
            font: 12px monospace;
            color: #8ea8ba;
            background: #061724;
            padding: 10px;
            border: 1px solid #17384f;
            border-radius: 6px;
            margin: 6px 0;
        }

        footer {
            text-align: center;
            color: #4f6c80;
            font-size: 10px;
            padding: 25px;
        }

        .section-title {
            display: flex;
            justify-content: space-between;
            align-items: center;
            gap: 20px;
            margin-bottom: 16px;
        }

        .dashboard-grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
            gap: 18px;
            margin-top: 18px;
        }

        .metric-row {
            margin: 16px 0;
        }

        .metric-label {
            display: flex;
            justify-content: space-between;
            margin-bottom: 7px;
        }

        .bar {
            height: 9px;
            background: #061724;
            border: 1px solid #17384f;
            border-radius: 10px;
            overflow: hidden;
        }

        .bar span {
            display: block;
            height: 100%;
            background: #20c9d6;
            border-radius: 10px;
        }

        .workload-table {
            width: 100%;
        }

        .work-row {
            display: grid;
            grid-template-columns: 1.8fr .7fr .7fr;
            gap: 12px;
            align-items: center;
            padding: 12px 0;
            border-bottom: 1px solid #17384f;
        }

        .work-row span small {
            display: block;
            color: #66879d;
            margin-top: 4px;
        }

        .work-row strong {
            color: #4ce0eb;
            font-size: 18px;
        }

        .work-head {
            color: #7895a8;
            font-size: 10px;
            text-transform: uppercase;
        }

        .work-head b {
            color: #7895a8;
        }

        .ai-badge {
            color: #58e1ea;
            border: 1px solid #216577;
            padding: 7px 10px;
            border-radius: 20px;
            font-size: 9px;
            letter-spacing: 1px;
        }

        @media(max-width:750px) {

            aside {
                position: static;
                width: 100%;
                height: auto;
            }

            aside a {
                display: inline-block;
            }

            main {
                margin-left: 0;
            }

            header {
                height: auto;
            }

            .wrap {
                padding: 18px;
            }
        }
        .evidence-media{max-width:100%;max-height:520px;border-radius:14px;margin-top:16px;border:1px solid rgba(255,255,255,.12);background:#0b1220;display:block;}
        input[type=file]{padding:12px;border:1px dashed rgba(255,255,255,.25);border-radius:10px;}
        """;
    }

    static void home(
            HttpExchange e)
            throws IOException {

        if (auth(e)) {
            red(e, "/dashboard");
            return;
        }

        send(e, """
        <!doctype html>
        <html>
        <head>
        <meta charset="UTF-8">

        <title>CrimeLens AI Login</title>

        <style>

        body {
            margin: 0;
            background: #061321;
            color: #e7f4fb;
            font-family: Arial;
            display: grid;
            place-items: center;
            min-height: 100vh;
        }

        .box {
            width: 360px;
            background: #0a2034;
            border: 1px solid #1b455f;
            border-radius: 15px;
            padding: 30px;
            box-shadow:
                0 20px 60px #0008;
        }

        h1 {
            margin: 0;
        }

        span {
            color: #2dd8e5;
        }

        p {
            color: #7895a8;
        }

        input {
            width: 100%;
            box-sizing: border-box;
            margin: 7px 0 14px;
            padding: 12px;
            background: #061724;
            border: 1px solid #234a63;
            border-radius: 7px;
            color: white;
        }

        .btn {
            width: 100%;
            padding: 12px;
            background: #13aebb;
            border: 0;
            border-radius: 7px;
            color: white;
            font-weight: bold;
        }

        </style>
        </head>

        <body>

        <form
            class="box"
            method="post"
            action="/login">

            <h1>
                CrimeLens <span>AI</span>
            </h1>

            <p>
                Smart Forensic Record
                Management System
            </p>

            <label>Username</label>
            <input
                name="username"
                required>

            <label>Password</label>
            <input
                type="password"
                name="password"
                required>

            <label>Security Code</label>
            <input
                name="code"
                required>

            <button class="btn">
                Secure Login
            </button>

        </form>

        </body>
        </html>
        """);
    }

    static void login(HttpExchange e) throws IOException {
        Map<String,String> m = form(e);
        String u = m.getOrDefault("username", "").trim();
        String p = m.getOrDefault("password", "");
        String c = m.getOrDefault("code", "");
        String r = null;

        if (USER.equals(u) && PASS.equals(p) && CODE.equals(c)) r = "Admin";
        else if ("investigator".equalsIgnoreCase(u) && "invest@2026".equals(p) && CODE.equals(c)) r = "Investigator";
        else if ("viewer".equalsIgnoreCase(u) && "view@2026".equals(p) && CODE.equals(c)) r = "Viewer";

        if (r != null) {
            String s = UUID.randomUUID().toString();
            sessions.put(s, System.currentTimeMillis());
            sessionRoles.put(s, r);
            e.getResponseHeaders().add("Set-Cookie", "SID=" + s + "; Path=/; HttpOnly");
            audit("LOGIN SUCCESS: " + u + " / " + r);
            red(e, "/dashboard");
        } else {
            audit("LOGIN FAILED");
            send(e, """
            <html><body style="background:#061321;color:white;font-family:Arial;padding:50px">
            <h2>Login failed</h2><p>Invalid credentials.</p>
            <a href="/" style="color:#42dbe5">Try again</a>
            </body></html>
            """);
        }
    }

    static void logout(
            HttpExchange e)
            throws IOException {

        String s = sid(e);

        if (s != null) {
            sessions.remove(s);
            sessionRoles.remove(s);
            currentRole.remove();
        }
        red(e, "/");
    }

    static void dash(
            HttpExchange e)
            throws IOException {

        if (!guard(e)) {
            return;
        }

        int open = 0, under = 0, closed = 0, critical = 0, high = 0;
        int medium = 0, low = 0;

        Map<String, Integer> categoryCount = new LinkedHashMap<>();
        Map<String, Integer> investigatorCount = new LinkedHashMap<>();

        for (C c : cs) {
            if (c.status.equalsIgnoreCase("Open")) open++;
            if (c.status.equalsIgnoreCase("Under Investigation")) under++;
            if (c.status.equalsIgnoreCase("Closed")) closed++;
            if (c.priority.equalsIgnoreCase("Critical")) critical++;
            if (c.priority.equalsIgnoreCase("High")) high++;
            if (c.priority.equalsIgnoreCase("Medium")) medium++;
            if (c.priority.equalsIgnoreCase("Low")) low++;

            String cat = c.cat.isBlank() ? "Other" : c.cat;
            categoryCount.put(cat, categoryCount.getOrDefault(cat, 0) + 1);

            String inv = c.investigator.isBlank() ? "Unassigned" : c.investigator;
            investigatorCount.put(inv, investigatorCount.getOrDefault(inv, 0) + 1);
        }

        StringBuilder b = new StringBuilder();
        b.append("""
        <div class="wrap">

        <div class="section-title">
            <div>
                <h2>Advanced Command Dashboard</h2>
                <p class="muted">Live metrics calculated from the current CrimeLens AI records.</p>
            </div>
            <a class="btn" href="/insight">Open AI Case Insight</a>
        </div>

        <div class="cards">
        """);

        b.append(stat("TOTAL CASES", cs.size()));
        b.append(stat("OPEN", open));
        b.append(stat("UNDER INVESTIGATION", under));
        b.append(stat("CLOSED", closed));
        b.append(stat("CRITICAL", critical));
        b.append(stat("HIGH PRIORITY", high));
        b.append(stat("EVIDENCE ITEMS", es.size()));
        b.append(stat("INVESTIGATORS", is.size()));

        b.append("""
        </div>

        <div class="dashboard-grid">
            <div class="panel">
                <h2>Case Status Distribution</h2>
                <p class="muted">Current case status distribution.</p>
        """);
        b.append(dashBar("Open", open, cs.size()));
        b.append(dashBar("Under Investigation", under, cs.size()));
        b.append(dashBar("Closed", closed, cs.size()));

        b.append("""
            </div>

            <div class="panel">
                <h2>Priority Distribution</h2>
                <p class="muted">Priority levels recorded in the case register.</p>
        """);
        b.append(dashBar("Critical", critical, cs.size()));
        b.append(dashBar("High", high, cs.size()));
        b.append(dashBar("Medium", medium, cs.size()));
        b.append(dashBar("Low", low, cs.size()));
        b.append("</div></div>");

        b.append("<div class='dashboard-grid'>");
        b.append("<div class='panel'><h2>Cases by Category</h2><p class='muted'>Automatically grouped from registered case categories.</p>");
        if (categoryCount.isEmpty()) {
            b.append("<div class='empty'>No category data yet.</div>");
        } else {
            for (Map.Entry<String,Integer> x : categoryCount.entrySet()) {
                b.append(dashBar(x.getKey(), x.getValue(), cs.size()));
            }
        }
        b.append("</div>");

        b.append("<div class='panel'><h2>Investigator Workload</h2><p class='muted'>Active workload based on investigator assignment in case records.</p>");
        if (is.isEmpty()) {
            b.append("<div class='empty'>No investigators registered.</div>");
        } else {
            b.append("<div class='workload-table'><div class='work-row work-head'><b>Investigator</b><b>Cases</b><b>Evidence</b></div>");
            for (I inv : is) {
                int casesFor = countAssignedCases(inv);
                int evidenceFor = evidenceForInvestigator(inv);
                b.append("<div class='work-row'><span><b>" + esc(inv.name) + "</b><small>" + esc(inv.id) + " • " + esc(inv.spec) + "</small></span><strong>" + casesFor + "</strong><strong>" + evidenceFor + "</strong></div>");
            }
            b.append("</div>");
        }
        b.append("</div></div>");

        b.append("<div class='panel'><div class='row'><div><h2>Recent Cases</h2><p class='muted'>Latest registered records.</p></div><a class='btn' href='/add-case'>+ Register Case</a></div>");
        int n = 0;
        for (int i = cs.size() - 1; i >= 0 && n < 5; i--, n++) {
            C c = cs.get(i);
            b.append("<div class='case'><div class='row'><div><small>" + esc(c.id) + "</small><h2>" + esc(c.title) + "</h2></div><span class='tag " + (c.priority.equalsIgnoreCase("Critical") ? "critical" : "") + "'>" + esc(c.priority) + "</span></div><p class='muted'>" + esc(c.cat) + " • " + esc(c.loc) + " • " + esc(c.status) + " • Evidence: " + c.evidence + "</p></div>");
        }
        if (cs.isEmpty()) b.append("<div class='empty'>No cases registered yet.</div>");
        b.append("</div></div>");

        send(e, page("Advanced Dashboard", b.toString()));
    }

    static String dashBar(String label, int value, int total) {
        int pct = total == 0 ? 0 : (value * 100 / total);
        return "<div class='metric-row'><div class='metric-label'><span>" + esc(label) + "</span><b>" + value + "</b></div><div class='bar'><span style='width:" + pct + "%'></span></div><small class='muted'>" + pct + "%</small></div>";
    }

    static int countAssignedCases(I inv) {
        int n = 0;
        for (C c : cs) {
            String a = c.investigator == null ? "" : c.investigator.toLowerCase();
            if (a.contains(inv.id.toLowerCase()) || a.contains(inv.name.toLowerCase())) n++;
        }
        return n;
    }

    static int evidenceForInvestigator(I inv) {
        int n = 0;
        for (E x : es) {
            String a = x.inv == null ? "" : x.inv.toLowerCase();
            if (a.contains(inv.id.toLowerCase()) || a.contains(inv.name.toLowerCase())) n++;
        }
        return n;
    }


    static String stat(
            String a,
            int n) {

        return
                "<div class='card'>"
                + "<small>"
                + esc(a)
                + "</small>"
                + "<b>"
                + n
                + "</b>"
                + "</div>";
    }

    static void addCase(
            HttpExchange e)
            throws IOException {

        if (!requireRole(e, "Admin", "Investigator")) {
            return;
        }

        if (e.getRequestMethod()
                .equals("POST")) {

            Map<String, String> m =
                    form(e);

            String id =
                    "CL-" + System.currentTimeMillis();

            C c =
                    new C(
                            id,
                            clean(m.get("title")),
                            clean(m.get("category")),
                            clean(m.get("location")),
                            clean(m.get("investigator")),
                            clean(m.get("suspect")),
                            clean(m.get("priority")),
                            clean(m.get("status")),
                            "0",
                            clean(m.get("description")),
                            now()
                    );

            cs.add(c);

            add(
                    CASES,
                    c.id,
                    c.title,
                    c.cat,
                    c.loc,
                    c.investigator,
                    c.suspect,
                    c.priority,
                    c.status,
                    "0",
                    c.desc,
                    c.date
            );

            add(
                    TIME,
                    "TL-" + System.currentTimeMillis(),
                    c.id,
                    "Case Registered",
                    "Case record created in CrimeLens AI",
                    now()
            );

            audit(
                    "CASE REGISTERED: " + c.id
            );

            red(e, "/cases");

            return;
        }

        String b =
                """
                <div class="wrap">

                <div class="panel">

                <form method="post">

                <div class="grid">
                """
                + input(
                        "title",
                        "Case Title",
                        true
                )
                + select(
                        "category",
                        "Category",
                        "Theft",
                        "Fraud",
                        "Cyber Crime",
                        "Missing Record",
                        "Other"
                )
                + input(
                        "location",
                        "Location",
                        true
                )
                + input(
                        "investigator",
                        "Investigator ID / Name",
                        true
                )
                + input(
                        "suspect",
                        "Reference / Suspect",
                        false
                )
                + select(
                        "priority",
                        "Priority",
                        "Low",
                        "Medium",
                        "High",
                        "Critical"
                )
                + select(
                        "status",
                        "Status",
                        "Open",
                        "Under Investigation",
                        "Closed"
                )
                + """
                </div>
                """
                + area(
                        "description",
                        "Case Description"
                )
                + """
                <button class="btn">
                    Save Case
                </button>

                </form>
                </div>
                </div>
                """;

        send(
                e,
                page(
                        "Register Case",
                        b
                )
        );
    }

    static void cases(
            HttpExchange e)
            throws IOException {

        if (!guard(e)) {
            return;
        }

        StringBuilder b =
                new StringBuilder();

        b.append("""
        <div class="wrap">
        <div class="panel">

        <div class="row">

        <div>
            <h2>Case Records</h2>

            <p class="muted">
                All saved case information.
            </p>
        </div>

        <a
            class="btn"
            href="/add-case">

            + New Case

        </a>

        </div>
        """);

        for (C c : cs) {

            b.append(
                    "<div class='case'>"
            );

            b.append(
                    "<div class='row'>"
            );

            b.append(
                    "<div>"
            );

            b.append(
                    "<small>"
            );

            b.append(
                    esc(c.id)
            );

            b.append(
                    "</small>"
            );

            b.append(
                    "<h2>"
            );

            b.append(
                    esc(c.title)
            );

            b.append(
                    "</h2>"
            );

            b.append(
                    "</div>"
            );

            b.append(
                    "<span class='tag "
            );

            if (c.priority.equalsIgnoreCase(
                    "Critical")) {

                b.append("critical");
            }

            b.append(
                    "'>"
            );

            b.append(
                    esc(c.priority)
            );

            b.append(
                    "</span>"
            );

            b.append(
                    "</div>"
            );

            b.append(
                    "<div class='kv'>"
            );

            b.append(
                    k("Category", c.cat)
            );

            b.append(
                    k("Location", c.loc)
            );

            b.append(
                    k(
                            "Investigator",
                            c.investigator
                    )
            );

            b.append(
                    k("Status", c.status)
            );

            b.append(
                    k(
                            "Evidence",
                            String.valueOf(
                                    c.evidence
                            )
                    )
            );

            b.append(
                    k("Created", c.date)
            );

            b.append(
                    "</div>"
            );

            b.append(
                    "<p class='muted'>"
            );

            b.append(
                    esc(c.desc)
            );

            b.append(
                    "</p>"
            );

            b.append(
                    "</div>"
            );
        }

        if (cs.isEmpty()) {

            b.append(
                    "<div class='empty'>"
                    + "No case records found."
                    + "</div>"
            );
        }

        b.append("""
        </div>
        </div>
        """);

        send(
                e,
                page(
                        "Case Records",
                        b.toString()
                )
        );
    }

    static void evidence(HttpExchange e) throws IOException {
        if (!requireRole(e, "Admin", "Investigator")) return;

        if (e.getRequestMethod().equals("POST")) {
            MultipartData mp = multipart(e);
            Map<String,String> m = mp.fields;

            String caseId = clean(m.get("caseId"));
            if (findCase(caseId) == null) {
                send(e, page("Evidence Management", "<div class='wrap'><div class='panel'><h2>Invalid Case ID</h2><p class='muted'>Please register the case before adding evidence.</p><a class='btn' href='/evidence'>Back to Evidence</a></div></div>"));
                return;
            }

            String id = "EV-" + (es.size() + 1);
            String originalName = clean(m.get("fileName"));
            byte[] media = mp.files.getOrDefault("media", new byte[0]);
            String contentType = clean(m.get("fileType"));
            String storedName = "";
            String storedPath = "";
            String fileHash = "";

            if (media.length > 0) {
                if (!isAllowedEvidenceType(contentType, originalName)) {
                    send(e, page("Evidence Management", "<div class='wrap'><div class='panel'><h2>Unsupported File</h2><p class='muted'>Only image files and video files are allowed as evidence media.</p><a class='btn' href='/evidence'>Back to Evidence</a></div></div>"));
                    return;
                }
                if (media.length > 100L * 1024L * 1024L) {
                    send(e, page("Evidence Management", "<div class='wrap'><div class='panel'><h2>File Too Large</h2><p class='muted'>Maximum evidence media size is 100 MB.</p><a class='btn' href='/evidence'>Back to Evidence</a></div></div>"));
                    return;
                }
                String safe = safeFileName(originalName);
                storedName = id + "_" + safe;
                Path target = MEDIA_DIR.resolve(storedName).normalize();
                if (!target.getParent().equals(MEDIA_DIR)) {
                    send(e, page("Evidence Management", "<div class='wrap'><div class='panel'><h2>Invalid File Name</h2></div></div>"));
                    return;
                }
                Files.write(target, media, StandardOpenOption.CREATE_NEW);
                storedPath = storedName;
                fileHash = sha256Bytes(media);
            }

            E x = new E(
                    id,
                    caseId,
                    clean(m.get("type")),
                    clean(m.get("category")),
                    clean(m.get("description")),
                    clean(m.get("location")),
                    clean(m.get("by")),
                    clean(m.get("inv")),
                    clean(m.get("condition")),
                    now()
            );
            x.fileName = storedName.isBlank() ? "" : originalName;
            x.fileType = contentType;
            x.filePath = storedPath;
            x.fileHash = fileHash;
            x.hash = sha256(row(x.id, x.caseId, x.type, x.cat, x.desc, x.loc, x.by, x.inv, x.condition, x.date, x.fileName, x.fileType, x.fileHash));

            es.add(x);
            add(EVID, x.id, x.caseId, x.type, x.cat, x.desc, x.loc, x.by, x.inv, x.condition, x.date, x.hash, x.fileName, x.fileType, x.filePath, x.fileHash);

            for (C c : cs) if (c.id.equals(x.caseId)) c.evidence++;

            audit("EVIDENCE ADDED: " + x.id + " / " + x.caseId + (x.fileName.isBlank() ? "" : " / MEDIA: " + x.fileName));
            red(e, "/evidence");
            return;
        }

        StringBuilder b = new StringBuilder();
        b.append("<div class='wrap'>");
        b.append("<div class='panel'><h2>Record Evidence Found</h2><p class='muted'>Save evidence metadata and securely upload an image or video linked to the case. Evidence media is accessible only to Admin and Investigator accounts.</p>");
        b.append("<form method='post' enctype='multipart/form-data'><div class='grid'>");
        b.append(input("caseId", "Case ID", true));
        b.append(select("type", "Evidence Type", "Photo", "Video / CCTV", "Document", "Digital Record", "Physical Object", "Audio", "Other"));
        b.append(input("category", "Evidence Category", false));
        b.append(input("location", "Found / Recorded Location", false));
        b.append(input("by", "Collected / Recorded By", false));
        b.append(input("inv", "Investigator ID", false));
        b.append(select("condition", "Status / Condition", "Recorded", "Secured", "Under Review", "Archived"));
        b.append("<div class='field'><label>Evidence Photo / Video</label><input type='file' name='media' accept='image/*,video/*'><small class='muted'>Optional • Images and videos • Maximum 100 MB</small></div>");
        b.append("</div>");
        b.append(area("description", "Evidence Description"));
        b.append("<button class='btn'>Save Evidence</button></form></div>");

        b.append("<div class='panel'><h2>Evidence Records</h2>");
        for (E x : es) {
            b.append("<div class='item'><div class='row'><div><small>" + esc(x.id) + " • " + esc(x.caseId) + "</small><h3>" + esc(x.type) + "</h3></div><span class='tag'>" + esc(x.condition) + "</span></div>");
            b.append("<p class='muted'>" + esc(x.desc) + "</p><div class='kv'>");
            b.append(k("Category", x.cat));
            b.append(k("Location", x.loc));
            b.append(k("Recorded By", x.by));
            b.append(k("Investigator", x.inv));
            b.append(k("Date", x.date));
            b.append(k("SHA-256", x.hash));
            if (!x.fileName.isBlank()) {
                b.append(k("Media", x.fileName));
                b.append(k("Media SHA-256", x.fileHash));
            }
            b.append("</div>");
            if (!x.filePath.isBlank()) {
                if (x.fileType.toLowerCase().startsWith("image/")) {
                    b.append("<img class='evidence-media' src='/evidence-file?id=" + esc(x.id) + "' alt='Evidence image'>");
                } else if (x.fileType.toLowerCase().startsWith("video/")) {
                    b.append("<video class='evidence-media' controls preload='metadata'><source src='/evidence-file?id=" + esc(x.id) + "' type='" + esc(x.fileType) + "'>Your browser does not support video playback.</video>");
                }
                b.append("<p><a class='btn' href='/evidence-file?id=" + esc(x.id) + "&download=1'>Access / Download Evidence</a></p>");
            } else {
                b.append("<p class='muted'>No photo/video file attached. Metadata only.</p>");
            }
            b.append("</div>");
        }
        if (es.isEmpty()) b.append("<div class='empty'>No evidence records found.</div>");
        b.append("</div></div>");
        send(e, page("Evidence Management", b.toString()));
    }

    static void evidenceFile(HttpExchange e) throws IOException {
        if (!requireRole(e, "Admin", "Investigator")) return;
        String id = q(e, "id");
        E x = null;
        for (E z : es) if (z.id.equals(id)) { x = z; break; }
        if (x == null || x.filePath.isBlank()) { e.sendResponseHeaders(404, -1); e.close(); return; }
        Path p = MEDIA_DIR.resolve(x.filePath).normalize();
        if (!p.getParent().equals(MEDIA_DIR) || !Files.exists(p)) { e.sendResponseHeaders(404, -1); e.close(); return; }
        byte[] data = Files.readAllBytes(p);
        String actual = sha256Bytes(data);
        if (!x.fileHash.isBlank() && !actual.equalsIgnoreCase(x.fileHash)) {
            audit("EVIDENCE MEDIA HASH MISMATCH: " + x.id);
            send(e, page("Evidence Integrity", "<div class='wrap'><div class='panel'><h2>Evidence Integrity Mismatch</h2><p class='muted'>The stored media file does not match its original SHA-256 fingerprint. Access was blocked.</p></div></div>"));
            return;
        }
        String type = x.fileType.isBlank() ? "application/octet-stream" : x.fileType;
        e.getResponseHeaders().set("Content-Type", type);
        if (q(e, "download").equals("1")) e.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + safeFileName(x.fileName.isBlank() ? x.filePath : x.fileName) + "\"");
        e.sendResponseHeaders(200, data.length);
        try (OutputStream out = e.getResponseBody()) { out.write(data); }
    }

    static boolean isAllowedEvidenceType(String type, String name) {
        String t = type == null ? "" : type.toLowerCase();
        String n = name == null ? "" : name.toLowerCase();
        return t.startsWith("image/") || t.startsWith("video/") ||
                n.matches(".*\\.(jpg|jpeg|png|gif|webp|bmp|mp4|webm|mov|avi|mkv|m4v)$");
    }

    static String safeFileName(String name) {
        if (name == null || name.isBlank()) return "evidence.bin";
        String n = Paths.get(name).getFileName().toString().replaceAll("[^a-zA-Z0-9._-]", "_");
        if (n.isBlank()) n = "evidence.bin";
        return n.length() > 120 ? n.substring(n.length() - 120) : n;
    }

    static String sha256Bytes(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception ex) { return ""; }
    }

    static class MultipartData {
        Map<String,String> fields = new HashMap<>();
        Map<String,byte[]> files = new HashMap<>();
    }

    static MultipartData multipart(HttpExchange e) throws IOException {
        String ct = e.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.toLowerCase().startsWith("multipart/form-data")) {
            MultipartData m = new MultipartData();
            m.fields.putAll(form(e));
            return m;
        }
        int bi = ct.indexOf("boundary=");
        if (bi < 0) throw new IOException("Missing multipart boundary");
        String boundary = ct.substring(bi + 9).trim();
        if (boundary.startsWith("\"")) boundary = boundary.substring(1, boundary.length() - 1);
        byte[] body = e.getRequestBody().readAllBytes();
        if (body.length > 110L * 1024L * 1024L) throw new IOException("Upload too large");
        byte[] marker = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        MultipartData out = new MultipartData();
        int pos = 0;
        while (true) {
            int start = indexOf(body, marker, pos);
            if (start < 0) break;
            start += marker.length;
            if (start + 2 <= body.length && body[start] == '-' && body[start + 1] == '-') break;
            if (start + 2 <= body.length && body[start] == '\r' && body[start + 1] == '\n') start += 2;
            int next = indexOf(body, marker, start);
            if (next < 0) break;
            int partEnd = next - 2;
            int headerEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), start);
            if (headerEnd < 0 || headerEnd > partEnd) break;
            String headers = new String(body, start, headerEnd - start, StandardCharsets.ISO_8859_1);
            int dataStart = headerEnd + 4;
            String name = dispositionValue(headers, "name");
            String filename = dispositionValue(headers, "filename");
            byte[] data = Arrays.copyOfRange(body, dataStart, Math.max(dataStart, partEnd));
            if (name != null && filename != null && !filename.isBlank()) {
                out.files.put(name, data);
                out.fields.put("fileName", filename);
                String partType = headerValue(headers, "Content-Type");
                if (partType != null) out.fields.put("fileType", partType);
            } else if (name != null) {
                out.fields.put(name, new String(data, StandardCharsets.UTF_8));
            }
            pos = next;
        }
        return out;
    }

    static String dispositionValue(String headers, String key) {
        for (String line : headers.split("\\r\\n")) {
            if (line.toLowerCase().startsWith("content-disposition:")) {
                for (String p : line.substring(line.indexOf(':') + 1).split(";")) {
                    String[] z = p.trim().split("=", 2);
                    if (z.length == 2 && z[0].trim().equalsIgnoreCase(key)) {
                        String v = z[1].trim();
                        if (v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
                        return v;
                    }
                }
            }
        }
        return null;
    }

    static String headerValue(String headers, String key) {
        for (String line : headers.split("\\r\\n")) {
            int i = line.indexOf(':');
            if (i > 0 && line.substring(0, i).trim().equalsIgnoreCase(key)) return line.substring(i + 1).trim();
        }
        return null;
    }

    static int indexOf(byte[] data, byte[] pattern, int from) {
        outer: for (int i = Math.max(0, from); i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) if (data[i + j] != pattern[j]) continue outer;
            return i;
        }
        return -1;
    }

    static void investigators(
            HttpExchange e)
            throws IOException {

        if (!requireRole(e, "Admin")) return;

        if (e.getRequestMethod().equals("POST")) {
            Map<String,String> m = form(e);
            String id = "INV-" + (is.size() + 1);
            I x = new I(
                    id,
                    clean(m.get("name")),
                    clean(m.get("role")),
                    clean(m.get("dept")),
                    clean(m.get("spec")),
                    clean(m.get("contact")),
                    now()
            );
            is.add(x);
            add(INV, x.id, x.name, x.role, x.dept, x.spec, x.contact, x.date);
            audit("INVESTIGATOR ADDED: " + x.id);
            red(e, "/investigators");
            return;
        }

        StringBuilder b = new StringBuilder();
        b.append("""
        <div class="wrap">
        <div class="panel">
            <h2>Investigator Management</h2>
            <p class="muted">Register investigators and monitor their case/evidence workload.</p>
            <form method="post">
            <div class="grid">
        """);
        b.append(input("name", "Investigator Name", true));
        b.append(input("role", "Role", true));
        b.append(input("dept", "Department", false));
        b.append(input("spec", "Specialization", false));
        b.append(input("contact", "Contact", false));
        b.append("""
            </div>
            <button class="btn">Save Investigator</button>
            </form>
        </div>

        <div class="panel">
            <h2>Investigator Workload</h2>
            <div class="workload-table">
                <div class="work-row work-head"><b>Investigator</b><b>Assigned Cases</b><b>Evidence</b></div>
        """);
        if (is.isEmpty()) {
            b.append("<div class='empty'>No investigators added.</div>");
        } else {
            for (I x : is) {
                int cc = countAssignedCases(x);
                int ee = evidenceForInvestigator(x);
                b.append("<div class='work-row'><span><b>" + esc(x.name) + "</b><small>" + esc(x.id) + " • " + esc(x.role) + " • " + esc(x.spec) + "</small></span><strong>" + cc + "</strong><strong>" + ee + "</strong></div>");
            }
        }
        b.append("</div></div>");

        b.append("<div class='panel'><h2>Registered Investigators</h2><div class='grid'>");
        for (I x : is) {
            int cc = countAssignedCases(x);
            int ee = evidenceForInvestigator(x);
            b.append("<div class='item'><small>" + esc(x.id) + "</small><h3>" + esc(x.name) + "</h3><p class='muted'>" + esc(x.role) + " • " + esc(x.dept) + "</p><p class='muted'>Specialization: " + esc(x.spec) + "<br>Contact: " + esc(x.contact) + "<br>Cases: <b>" + cc + "</b> • Evidence: <b>" + ee + "</b><br>Added: " + esc(x.date) + "</p></div>");
        }
        if (is.isEmpty()) b.append("<div class='empty'>No investigators added.</div>");
        b.append("</div></div></div>");

        send(e, page("Investigator Management", b.toString()));
    }


    static void timeline(
            HttpExchange e)
            throws IOException {

        if (!guard(e)) {
            return;
        }

        if (e.getRequestMethod()
                .equals("POST")) {
            String rr = role(e);
            if (!isEditor(rr)) { red(e, "/timeline"); return; }

            Map<String, String> m =
                    form(e);

            String id =
                    "TL-" + (ts.size() + 1);

            T x =
                    new T(
                            id,
                            clean(m.get("caseId")),
                            clean(m.get("event")),
                            clean(m.get("description")),
                            now()
                    );

            ts.add(x);

            add(
                    TIME,
                    x.id,
                    x.caseId,
                    x.event,
                    x.desc,
                    x.date
            );

            audit(
                    "TIMELINE ADDED: "
                    + x.id
            );

            red(e, "/timeline");

            return;
        }

        StringBuilder b =
                new StringBuilder();

        b.append("""
        <div class="wrap">

        <div class="panel">

        <h2>
            Add Case Timeline Event
        </h2>

        <form method="post">

        <div class="grid">
        """);

        b.append(
                input(
                        "caseId",
                        "Case ID",
                        true
                )
        );

        b.append(
                input(
                        "event",
                        "Event",
                        true
                )
        );

        b.append("""
        </div>
        """);

        b.append(
                area(
                        "description",
                        "Event Description"
                )
        );

        b.append("""
        <button class="btn">
            Add Event
        </button>

        </form>

        </div>

        <div class="panel">

        <h2>
            Timeline
        </h2>

        <div class="timeline">
        """);

        for (T x : ts) {

            b.append(
                    "<div class='event'>"
            );

            b.append(
                    "<small>"
                    + esc(x.date)
                    + " • "
                    + esc(x.caseId)
                    + "</small>"
            );

            b.append(
                    "<h3>"
                    + esc(x.event)
                    + "</h3>"
            );

            b.append(
                    "<p class='muted'>"
                    + esc(x.desc)
                    + "</p>"
            );

            b.append(
                    "</div>"
            );
        }

        if (ts.isEmpty()) {

            b.append(
                    "<div class='empty'>"
                    + "No timeline events."
                    + "</div>"
            );
        }

        b.append("""
        </div>
        </div>
        </div>
        """);

        send(
                e,
                page(
                        "Case Timeline",
                        b.toString()
                )
        );
    }

    static void insight(
            HttpExchange e)
            throws IOException {

        if (!guard(e)) return;

        String id = q(e, "caseId");
        C c = cs.isEmpty() ? null : findCase(id);
        if (c == null && !cs.isEmpty()) c = cs.get(cs.size() - 1);

        StringBuilder b = new StringBuilder();
        b.append("""
        <div class="wrap">
        <div class="panel ai">
            <div class="section-title">
                <div>
                    <h2>AI Case Insight</h2>
                    <p class="muted">Rule-based AI-style analysis generated from stored case metadata. This prototype does not use machine learning and does not predict guilt or innocence.</p>
                </div>
                <span class="ai-badge">ANALYSIS ENGINE</span>
            </div>
            <form>
                <div class="grid">
                    <div class="field">
                        <label>Select Case</label>
                        <select name="caseId">
        """);
        for (C x : cs) {
            b.append("<option value='" + esc(x.id) + "' " + (c != null && x.id.equals(c.id) ? "selected" : "") + ">" + esc(x.id) + " — " + esc(x.title) + "</option>");
        }
        b.append("""
                        </select>
                    </div>
                </div>
                <button class="btn">Generate Insight</button>
            </form>
        """);

        if (c != null) {
            int ev = 0, timelineEvents = 0;
            for (E x : es) if (x.caseId.equals(c.id)) ev++;
            for (T x : ts) if (x.caseId.equals(c.id)) timelineEvents++;

            int score = 0;
            if (c.priority.equalsIgnoreCase("Critical")) score += 40;
            else if (c.priority.equalsIgnoreCase("High")) score += 25;
            else if (c.priority.equalsIgnoreCase("Medium")) score += 15;
            else score += 5;
            if (!c.investigator.isBlank()) score += 15;
            if (ev > 0) score += 20;
            if (timelineEvents > 0) score += 15;
            if (!c.desc.isBlank()) score += 10;
            score = Math.min(score, 100);

            String level = score >= 70 ? "Well Documented" : score >= 45 ? "Moderately Documented" : "Needs Attention";

            b.append("<div class='kv'>");
            b.append(k("Priority", c.priority));
            b.append(k("Status", c.status));
            b.append(k("Evidence", String.valueOf(ev)));
            b.append(k("Timeline Events", String.valueOf(timelineEvents)));
            b.append(k("Documentation Score", score + "%"));
            b.append(k("Assessment", level));
            b.append("</div>");

            b.append("<div class='insight'><b>AI Perspective</b><br>");
            b.append("The case is recorded as <b>" + esc(c.priority) + "</b> priority and <b>" + esc(c.status) + "</b>. ");
            b.append("The system found <b>" + ev + "</b> linked evidence item(s) and <b>" + timelineEvents + "</b> timeline event(s). ");
            if (c.status.equalsIgnoreCase("Closed")) {
                b.append("The current record is marked closed.");
            } else {
                b.append("The current record remains active and can receive further timeline or evidence updates.");
            }
            b.append("</div>");

            b.append("<div class='dashboard-grid'>");
            b.append("<div class='insight'><b>Completeness Check</b><br>");
            b.append(c.investigator.isBlank() ? "⚠ Investigator assignment is missing.<br>" : "✓ Investigator assignment is present.<br>");
            b.append(c.desc.isBlank() ? "⚠ Case description is missing.<br>" : "✓ Case description is present.<br>");
            b.append(ev == 0 ? "⚠ No evidence is linked to this case.<br>" : "✓ Evidence is linked to the case.<br>");
            b.append(timelineEvents == 0 ? "⚠ No timeline event has been recorded.<br>" : "✓ Timeline activity is recorded.<br>");
            b.append("</div>");

            b.append("<div class='insight'><b>Suggested Next Actions</b><br>");
            if (c.investigator.isBlank()) b.append("• Assign an investigator.<br>");
            if (ev == 0) b.append("• Add available evidence metadata.<br>");
            if (timelineEvents == 0) b.append("• Add the latest investigation timeline event.<br>");
            if (!c.status.equalsIgnoreCase("Closed")) b.append("• Keep case status and timeline synchronized.<br>");
            if (c.investigator.isBlank() && ev == 0 && timelineEvents == 0) b.append("• Complete the basic case record before further analysis.<br>");
            if (!c.investigator.isBlank() && ev > 0 && timelineEvents > 0 && !c.status.equalsIgnoreCase("Closed")) b.append("• Continue updating the record as investigation activities occur.<br>");
            b.append("</div></div>");

            b.append("<div class='insight'><b>Data Summary</b><br>Category: " + esc(c.cat) + "<br>Location: " + esc(c.loc) + "<br>Reference / Suspect: " + esc(c.suspect) + "<br>Description: " + esc(c.desc) + "</div>");
        } else {
            b.append("<div class='empty'>Register a case first to generate an insight.</div>");
        }

        b.append("</div></div>");
        send(e, page("AI Case Insight", b.toString()));
    }


    static C findCase(String id) {

        for (C c : cs) {

            if (c.id.equals(id)) {
                return c;
            }
        }

        return null;
    }

    static void search(HttpExchange e) throws IOException {
        if (!guard(e)) return;
        String term=q(e,"q").toLowerCase().trim();
        String category=q(e,"category").toLowerCase().trim();
        String status=q(e,"status").toLowerCase().trim();
        String priority=q(e,"priority").toLowerCase().trim();
        String location=q(e,"location").toLowerCase().trim();
        String investigator=q(e,"investigator").toLowerCase().trim();
        StringBuilder b=new StringBuilder();
        b.append("<div class='wrap'><div class='panel'><h2>Smart Search 2.0</h2><p class='muted'>Search cases, evidence and timeline records using multiple filters.</p><form><div class='grid'>");
        b.append(inputValue("q","Keyword",term,false));
        b.append(inputValue("category","Category",category,false));
        b.append(inputValue("location","Location",location,false));
        b.append(inputValue("investigator","Investigator",investigator,false));
        b.append(selectValue("status","Status",status,"","Open","Under Investigation","Closed"));
        b.append(selectValue("priority","Priority",priority,"","Low","Medium","High","Critical"));
        b.append("</div><button class='btn'>Search Records</button></form></div>");

        int n=0;
        b.append("<div class='panel'><h2>Case Results</h2>");
        for(C c:cs){
            String all=(c.id+" "+c.title+" "+c.cat+" "+c.loc+" "+c.investigator+" "+c.suspect+" "+c.desc).toLowerCase();
            if(match(all,term) && match(c.cat.toLowerCase(),category) && match(c.status.toLowerCase(),status) && match(c.priority.toLowerCase(),priority) && match(c.loc.toLowerCase(),location) && match(c.investigator.toLowerCase(),investigator)){
                n++; b.append("<div class='item'><span class='tag'>CASE</span><h3>"+esc(c.id)+" — "+esc(c.title)+"</h3><p class='muted'>"+esc(c.cat)+" • "+esc(c.loc)+" • "+esc(c.investigator)+" • "+esc(c.status)+" • "+esc(c.priority)+" • Evidence: "+c.evidence+"</p></div>");
            }
        }
        if(n==0)b.append("<div class='empty'>No matching case records.</div>");
        b.append("</div><div class='panel'><h2>Evidence Results</h2>");
        int en=0;
        for(E x:es){String all=(x.id+" "+x.caseId+" "+x.type+" "+x.cat+" "+x.desc+" "+x.loc+" "+x.by+" "+x.inv+" "+x.condition).toLowerCase(); if(match(all,term)||(!category.isEmpty()&&x.cat.toLowerCase().contains(category))||(!location.isEmpty()&&x.loc.toLowerCase().contains(location))||(!investigator.isEmpty()&&x.inv.toLowerCase().contains(investigator))){en++; b.append("<div class='item'><span class='tag'>EVIDENCE</span><h3>"+esc(x.id)+" — Case "+esc(x.caseId)+"</h3><p class='muted'>"+esc(x.type)+" • "+esc(x.cat)+" • "+esc(x.condition)+" • "+esc(x.loc)+"</p></div>");}}
        if(en==0)b.append("<div class='empty'>No matching evidence records.</div>");
        b.append("</div><div class='panel'><h2>Timeline Results</h2>");
        int tn=0;
        for(T x:ts){String all=(x.id+" "+x.caseId+" "+x.event+" "+x.desc+" "+x.date).toLowerCase(); if(match(all,term)){tn++; b.append("<div class='item'><span class='tag'>TIMELINE</span><h3>"+esc(x.event)+" — "+esc(x.caseId)+"</h3><p class='muted'>"+esc(x.date)+" • "+esc(x.desc)+"</p></div>");}}
        if(tn==0)b.append("<div class='empty'>No matching timeline records.</div>");
        b.append("</div></div>");
        send(e,page("Smart Search 2.0",b.toString()));
    }

    static boolean match(String value,String filter){return filter==null||filter.isEmpty()||value.contains(filter);}
    static String inputValue(String n,String l,String v,boolean req){return "<div class='field'><label>"+esc(l)+"</label><input name='"+n+"' value='"+esc(v)+"' "+(req?"required":"")+"></div>";}
    static String selectValue(String n,String l,String selected,String blank,String... vals){StringBuilder b=new StringBuilder("<div class='field'><label>"+esc(l)+"</label><select name='"+n+"'><option value=''>All</option>");for(String v:vals)b.append("<option "+(v.equalsIgnoreCase(selected)?"selected":"")+">"+esc(v)+"</option>");return b.append("</select></div>").toString();}

    static void export(HttpExchange e) throws IOException {
        if (!requireRole(e,"Admin","Investigator")) return;
        if(e.getRequestMethod().equals("POST")){
            audit("DATA EXPORT REQUESTED");
            StringBuilder out=new StringBuilder("SECTION,ID,CASE ID,TYPE/CATEGORY,DESCRIPTION,LOCATION,USER/INVESTIGATOR,STATUS/CONDITION,DATE,SHA-256\\n");
            for(C c:cs) out.append(row("CASE",c.id,c.id,c.cat,c.desc,c.loc,c.investigator,c.status,c.date,"")).append("\\n");
            for(E x:es) out.append(row("EVIDENCE",x.id,x.caseId,x.type+" / "+x.cat,x.desc,x.loc,x.inv,x.condition,x.date,x.hash)).append("\\n");
            for(T x:ts) out.append(row("TIMELINE",x.id,x.caseId,x.event,x.desc,"","","",x.date,"")).append("\\n");
            byte[] d=out.toString().getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type","text/csv; charset=UTF-8");
            e.getResponseHeaders().set("Content-Disposition","attachment; filename=CrimeLensAI_Records.csv");
            e.sendResponseHeaders(200,d.length); try(OutputStream o=e.getResponseBody()){o.write(d);} return;
        }
        send(e,page("Data Export","""
        <div class="wrap"><div class="panel"><h2>Export Records</h2>
        <p class="muted">Download cases, evidence and timeline records as a CSV file for analysis, documentation and reporting.</p>
        <ul class="muted"><li>Case records</li><li>Evidence records with SHA-256 fingerprint</li><li>Timeline records</li></ul>
        <form method="post"><button class="btn">Download CSV Export</button></form></div></div>
        """));
    }


    static void custody(HttpExchange e) throws IOException {
        if (!guard(e)) return;
        String rr=role(e);
        if(e.getRequestMethod().equals("POST")){
            if(!isEditor(rr)){ red(e,"/custody"); return; }
            Map<String,String> m=form(e);
            String id="COC-"+(custody.size()+1);
            Custody x=new Custody(id,clean(m.get("evidenceId")),clean(m.get("action")),
                    clean(m.get("fromUser")),clean(m.get("toUser")),clean(m.get("location")),
                    clean(m.get("note")),now());
            custody.add(x);
            add(CUSTODY,x.id,x.evidenceId,x.action,x.fromUser,x.toUser,x.location,x.note,x.date);
            audit("CHAIN OF CUSTODY UPDATED: "+x.id+" / "+x.evidenceId);
            red(e,"/custody"); return;
        }
        StringBuilder b=new StringBuilder("<div class='wrap'>");
        if(isEditor(rr)){
            b.append("<div class='panel'><h2>Evidence Chain of Custody</h2><p class='muted'>Record each transfer, storage movement or handling event for an evidence item.</p><form method='post'><div class='grid'>");
            b.append(input("evidenceId","Evidence ID",true));
            b.append(input("action","Action / Movement",true));
            b.append(input("fromUser","Released / From",false));
            b.append(input("toUser","Received / To",false));
            b.append(input("location","Location",false));
            b.append(input("note","Remarks",false));
            b.append("</div><button class='btn'>Add Custody Event</button></form></div>");
        }
        b.append("<div class='panel'><h2>Custody History</h2>");
        if(custody.isEmpty()) b.append("<div class='empty'>No chain-of-custody events recorded.</div>");
        for(int i=custody.size()-1;i>=0;i--){Custody x=custody.get(i);b.append("<div class='item'><div class='row'><div><small>"+esc(x.id)+" • "+esc(x.evidenceId)+"</small><h3>"+esc(x.action)+"</h3></div><span class='tag'>CHAIN VERIFIED RECORD</span></div><p class='muted'>"+esc(x.date)+" • From: "+esc(x.fromUser)+" • To: "+esc(x.toUser)+" • Location: "+esc(x.location)+"<br>"+esc(x.note)+"</p></div>");}
        b.append("</div></div>");
        send(e,page("Chain of Custody",b.toString()));
    }

    static void verify(HttpExchange e) throws IOException {
        if(!requireRole(e, "Admin", "Investigator")) return;
        StringBuilder b=new StringBuilder("<div class='wrap'><div class='panel'><h2>Evidence Integrity / SHA-256 Verification</h2><p class='muted'>The system recalculates the evidence fingerprint from its stored metadata and compares it with the saved SHA-256 value.</p>");
        if(es.isEmpty()){b.append("<div class='empty'>No evidence records available for verification.</div>");}
        else for(E x:es){
            String recalculated=sha256(row(x.id,x.caseId,x.type,x.cat,x.desc,x.loc,x.by,x.inv,x.condition,x.date));
            boolean ok=recalculated.equalsIgnoreCase(x.hash);
            boolean mediaOk=true;
            if(!x.filePath.isBlank()){ try { mediaOk=x.fileHash.equalsIgnoreCase(sha256Bytes(Files.readAllBytes(MEDIA_DIR.resolve(x.filePath).normalize()))); } catch(Exception ex){ mediaOk=false; } }
            boolean allOk=ok && mediaOk;
            b.append("<div class='item'><div class='row'><div><small>"+esc(x.id)+" • Case "+esc(x.caseId)+"</small><h3>"+esc(x.type)+"</h3></div><span class='tag "+(allOk?"":"critical")+"'>"+(allOk?"VERIFIED":"MISMATCH")+"</span></div><p class='muted'>Record SHA-256: "+esc(x.hash)+"<br>Calculated Record SHA-256: "+esc(recalculated)+(x.filePath.isBlank()?"":"<br>Media SHA-256: "+esc(x.fileHash)+"<br>Current Media SHA-256: "+esc(mediaOk?x.fileHash:"MISMATCH"))+"</p></div>");
        }
        b.append("</div></div>");
        send(e,page("Evidence Integrity",b.toString()));
    }

    static void alerts(HttpExchange e) throws IOException {
        if(!guard(e)) return;
        StringBuilder b=new StringBuilder("<div class='wrap'><div class='panel'><h2>Automatic Case Alerts</h2><p class='muted'>Rule-based alerts generated automatically from current case, evidence and investigator records.</p>");
        int n=0;
        for(C c:cs){
            int ev=0; for(E x:es) if(x.caseId.equals(c.id)) ev++;
            int tl=0; for(T x:ts) if(x.caseId.equals(c.id)) tl++;
            if(c.priority.equalsIgnoreCase("Critical")) {n++; b.append(alertCard("CRITICAL PRIORITY",c,"Immediate review recommended for this high-priority record."));}
            if(c.investigator.isBlank()) {n++; b.append(alertCard("UNASSIGNED CASE",c,"No investigator is currently recorded for this case."));}
            if(ev==0 && !c.status.equalsIgnoreCase("Closed")) {n++; b.append(alertCard("NO EVIDENCE LINKED",c,"No evidence metadata is linked to the active case."));}
            if(tl==0 && !c.status.equalsIgnoreCase("Closed")) {n++; b.append(alertCard("TIMELINE INCOMPLETE",c,"No investigation timeline event is recorded yet."));}
        }
        if(n==0)b.append("<div class='empty'>No active rule-based alerts. Current records meet the configured alert checks.</div>");
        b.append("</div></div>");
        send(e,page("Automatic Case Alerts",b.toString()));
    }

    static String alertCard(String type,C c,String msg){
        return "<div class='item'><div class='row'><div><span class='tag critical'>"+esc(type)+"</span><h3>"+esc(c.id)+" — "+esc(c.title)+"</h3></div><span class='tag'>"+esc(c.status)+"</span></div><p class='muted'>"+esc(msg)+"</p></div>";
    }

    static void report(HttpExchange e) throws IOException {
        if(!guard(e)) return;
        String id=q(e,"caseId");
        C c=findCase(id);
        if(c==null && !cs.isEmpty()) c=cs.get(cs.size()-1);
        StringBuilder b=new StringBuilder("<div class='wrap'>");
        b.append("<div class='panel'><div class='row'><div><h2>Automatic Case Report Generator</h2><p class='muted'>Generate a structured report from the records already stored in CrimeLens AI.</p></div><button class='btn' onclick='window.print()'>Print / Save PDF</button></div><form><div class='grid'><div class='field'><label>Select Case</label><select name='caseId'>");
        for(C x:cs)b.append("<option value='"+esc(x.id)+"' "+(c!=null&&x.id.equals(c.id)?"selected":"")+">"+esc(x.id)+" — "+esc(x.title)+"</option>");
        b.append("</select></div></div><button class='btn'>Generate Report</button></form></div>");
        if(c!=null){
            b.append("<div class='panel'><h2>CASE INVESTIGATION REPORT</h2>");
            b.append("<div class='kv'>"+k("Case ID",c.id)+k("Title",c.title)+k("Category",c.cat)+k("Location",c.loc)+k("Investigator",c.investigator)+k("Priority",c.priority)+k("Status",c.status)+k("Created",c.date)+"</div>");
            b.append("<div class='insight'><b>Case Description</b><br>"+esc(c.desc)+"</div>");
            b.append("<h3>Evidence Records</h3>");
            int ev=0; for(E x:es) if(x.caseId.equals(c.id)){ev++;b.append("<div class='item'><b>"+esc(x.id)+"</b> • "+esc(x.type)+" • "+esc(x.condition)+"<br><span class='muted'>"+esc(x.desc)+" • SHA-256: "+esc(x.hash)+"</span></div>");}
            if(ev==0)b.append("<div class='empty'>No evidence records linked.</div>");
            b.append("<h3>Case Timeline</h3><div class='timeline'>");
            int tl=0; for(T x:ts) if(x.caseId.equals(c.id)){tl++;b.append("<div class='event'><small>"+esc(x.date)+"</small><h3>"+esc(x.event)+"</h3><p class='muted'>"+esc(x.desc)+"</p></div>");}
            if(tl==0)b.append("<div class='empty'>No timeline events linked.</div>");
            b.append("</div></div>");
        }else b.append("<div class='panel'><div class='empty'>Register a case first to generate a report.</div></div>");
        b.append("</div>");
        send(e,page("Case Report Generator",b.toString()));
    }

    static String sha256(String text){
        try{
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            byte[] d=md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder b=new StringBuilder();
            for(byte x:d)b.append(String.format("%02x",x));
            return b.toString();
        }catch(Exception ex){return "";}
    }

    static void audit(
            HttpExchange e)
            throws IOException {

        if (!requireRole(e, "Admin")) {
            return;
        }

        StringBuilder b =
                new StringBuilder();

        b.append("""
        <div class="wrap">

        <div class="panel">

        <h2>
            Audit Trail
        </h2>

        <p class="muted">
            System activity log.
        </p>
        """);

        try {

            List<String> l =
                    Files.readAllLines(AUDIT);

            for (
                    int i = l.size() - 1;
                    i >= 0;
                    i--
            ) {

                b.append(
                        "<div class='log'>"
                );

                b.append(
                        esc(l.get(i))
                );

                b.append(
                        "</div>"
                );
            }

        } catch (Exception ignored) {
        }

        b.append("""
        </div>
        </div>
        """);

        send(
                e,
                page(
                        "Audit Trail",
                        b.toString()
                )
        );
    }

    static String role(HttpExchange e) {
        String s = sid(e);
        return s == null ? null : sessionRoles.get(s);
    }

    static boolean requireRole(HttpExchange e, String... allowed) throws IOException {
        if (!guard(e)) return false;
        String r = role(e);
        for (String a : allowed) if (a.equals(r)) return true;
        // Unauthorized users are redirected instead of shown an Access Denied page.
        red(e, "/dashboard");
        return false;
    }

    static boolean guard(HttpExchange e) throws IOException {
        if (!auth(e)) {
            red(e, "/");
            return false;
        }
        String s = sid(e);
        sessions.put(s, System.currentTimeMillis());
        currentRole.set(sessionRoles.getOrDefault(s, "Admin"));
        return true;
    }

    static void audit(String s) {

        try {

            Files.writeString(
                    AUDIT,
                    now()
                    + " | "
                    + s
                    + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );

        } catch (Exception ignored) {
        }
    }

    static String now() {

        return LocalDateTime
                .now()
                .format(F);
    }

    static String q(
            HttpExchange e,
            String k)
            throws IOException {

        String x =
                e.getRequestURI()
                        .getRawQuery();

        if (x == null) {
            return "";
        }

        for (String p : x.split("&")) {

            String[] z =
                    p.split("=", 2);

            if (
                    z.length == 2
                    && URLDecoder.decode(
                            z[0],
                            "UTF-8"
                    ).equals(k)
            ) {

                return URLDecoder.decode(
                        z[1],
                        "UTF-8"
                );
            }
        }

        return "";
    }

    static String input(
            String n,
            String l,
            boolean req) {

        return
                "<div class='field'>"
                + "<label>"
                + l
                + "</label>"
                + "<input name='"
                + n
                + "' "
                + (
                    req
                    ? "required"
                    : ""
                )
                + ">"
                + "</div>";
    }

    static String area(
            String n,
            String l) {

        return
                "<div class='field'>"
                + "<label>"
                + l
                + "</label>"
                + "<textarea name='"
                + n
                + "'></textarea>"
                + "</div>";
    }

    static String select(
            String n,
            String l,
            String... v) {

        StringBuilder b =
                new StringBuilder();

        b.append(
                "<div class='field'>"
        );

        b.append(
                "<label>"
        );

        b.append(l);

        b.append(
                "</label>"
        );

        b.append(
                "<select name='"
        );

        b.append(n);

        b.append(
                "'>"
        );

        for (String x : v) {

            b.append(
                    "<option>"
            );

            b.append(x);

            b.append(
                    "</option>"
            );
        }

        b.append(
                "</select>"
        );

        b.append(
                "</div>"
        );

        return b.toString();
    }

    static String k(
            String a,
            String b) {

        return
                "<div>"
                + "<small>"
                + esc(a)
                + "</small>"
                + "<b>"
                + esc(b)
                + "</b>"
                + "</div>";
    }
}