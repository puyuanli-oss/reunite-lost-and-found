package edu.usc.lostfound;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** Small JDBC boundary. Every application parameter is bound to a prepared statement. */
public final class Database {
    public static final String DEFAULT_URL = "jdbc:h2:file:./data/lostfound;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
    private final String url;
    private final String user;
    private final String password;

    @FunctionalInterface
    public interface Work<T> { T run(Connection connection) throws Exception; }

    public static final class DatabaseException extends RuntimeException {
        public DatabaseException(String message, Throwable cause) { super(message, cause); }
    }

    public Database() {
        this(env("DB_URL", DEFAULT_URL), env("DB_USER", "sa"), env("DB_PASSWORD", ""));
    }

    /** Explicit configuration is useful for isolated integration tests. */
    public Database(String url, String user, String password) {
        this.url = Objects.requireNonNull(url);
        this.user = Objects.requireNonNull(user);
        this.password = Objects.requireNonNull(password);
        try {
            if (url.startsWith("jdbc:h2:")) {
                Class.forName("org.h2.Driver");
                if (url.equals(DEFAULT_URL)) Files.createDirectories(Path.of("data"));
            } else if (url.startsWith("jdbc:mysql:")) {
                Class.forName("com.mysql.cj.jdbc.Driver");
            } else {
                throw new IllegalArgumentException("Only H2 and MySQL JDBC URLs are supported.");
            }
            initialize();
        } catch (ClassNotFoundException e) {
            throw new DatabaseException("The JDBC driver is missing. Run the setup script and include lib/* on the Java classpath.", e);
        } catch (IOException e) {
            throw new DatabaseException("The data directory or schema could not be opened.", e);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    public Connection connect() {
        try { return DriverManager.getConnection(url, user, password); }
        catch (SQLException e) { throw new DatabaseException("Unable to connect to the database.", e); }
    }

    private void initialize() throws IOException {
        String schema;
        try (InputStream in = Database.class.getResourceAsStream("/schema.sql")) {
            if (in == null) throw new IOException("Missing classpath resource /schema.sql");
            schema = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        schema = schema.replace("PHOTO_BINARY", url.startsWith("jdbc:mysql:") ? "LONGBLOB" : "BLOB");
        schema = schema.replaceAll("(?m)^\\s*--.*$", "");
        try (Connection c = connect(); Statement statement = c.createStatement()) {
            for (String part : schema.split(";")) {
                String sql = part.trim();
                if (sql.isEmpty()) continue;
                // MySQL 8 does not accept CREATE INDEX IF NOT EXISTS. Ignore only its
                // specific duplicate-index code, never arbitrary schema errors.
                if (url.startsWith("jdbc:mysql:") && sql.startsWith("CREATE INDEX IF NOT EXISTS")) {
                    sql = sql.replace("CREATE INDEX IF NOT EXISTS", "CREATE INDEX");
                    try { statement.execute(sql); }
                    catch (SQLException e) { if (e.getErrorCode() != 1061) throw e; }
                } else statement.execute(sql);
            }
        } catch (SQLException e) { throw new DatabaseException("Database schema initialization failed.", e); }
    }

    public List<Map<String,Object>> query(String sql, Object... args) {
        try (Connection c = connect()) { return query(c, sql, args); }
        catch (SQLException e) { throw new DatabaseException("Database query failed.", e); }
    }

    public Map<String,Object> one(String sql, Object... args) {
        List<Map<String,Object>> rows = query(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int update(String sql, Object... args) {
        try (Connection c = connect()) { return update(c, sql, args); }
        catch (SQLException e) { throw new DatabaseException("Database update failed.", e); }
    }

    public long insert(String sql, Object... args) {
        try (Connection c = connect()) { return insert(c, sql, args); }
        catch (SQLException e) { throw new DatabaseException("Database insert failed.", e); }
    }

    public static List<Map<String,Object>> query(Connection c, String sql, Object... args) {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            bind(p, args);
            try (ResultSet rs = p.executeQuery()) {
                List<Map<String,Object>> rows = new ArrayList<>();
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    Map<String,Object> row = new LinkedHashMap<>();
                    for (int i=1; i<=meta.getColumnCount(); i++) {
                        Object value = rs.getObject(i);
                        // JDBC LOB handles may become invalid once the connection closes.
                        if (value instanceof Clob clob) {
                            value = clob.getSubString(1, Math.toIntExact(clob.length()));
                            clob.free();
                        } else if (value instanceof Blob blob) {
                            value = blob.getBytes(1, Math.toIntExact(blob.length()));
                            blob.free();
                        }
                        row.put(camel(meta.getColumnLabel(i)), value);
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) { throw new DatabaseException("Database query failed.", e); }
    }

    public static int update(Connection c, String sql, Object... args) {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            bind(p, args);
            return p.executeUpdate();
        } catch (SQLException e) { throw new DatabaseException("Database update failed.", e); }
    }

    public static long insert(Connection c, String sql, Object... args) {
        try (PreparedStatement p = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(p, args);
            if (p.executeUpdate() != 1) throw new SQLException("Expected one inserted row.");
            try (ResultSet keys = p.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("No generated key was returned.");
                return keys.getLong(1);
            }
        } catch (SQLException e) { throw new DatabaseException("Database insert failed.", e); }
    }

    public <T> T tx(Work<T> work) {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                T value = work.run(c);
                c.commit();
                return value;
            } catch (Exception e) {
                try { c.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                if (e instanceof RuntimeException runtime) throw runtime;
                throw new DatabaseException("Database transaction failed and was rolled back.", e);
            }
        } catch (SQLException e) { throw new DatabaseException("Database transaction failed.", e); }
    }

    private static void bind(PreparedStatement p, Object[] args) throws SQLException {
        for (int i=0; i<args.length; i++) {
            Object value = args[i];
            if (value instanceof LocalDate date) p.setDate(i+1, java.sql.Date.valueOf(date));
            else if (value instanceof java.time.Instant instant) p.setTimestamp(i+1, Timestamp.from(instant));
            else p.setObject(i+1, value);
        }
    }

    private static String camel(String label) {
        // Preserve intentional camelCase aliases; standard schema labels are snake_case.
        if (label.indexOf('_') < 0 && !label.equals(label.toUpperCase(Locale.ROOT))) return label;
        String lower = label.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char ch : lower.toCharArray()) {
            if (ch == '_') { upper = true; continue; }
            out.append(upper ? Character.toUpperCase(ch) : ch);
            upper = false;
        }
        return out.toString();
    }

    /** Local demonstration fixtures only. Call explicitly when DEMO_MODE is enabled. */
    public void seed() {
        tx(c -> {
            List<Map<String,Object>> existing = query(c, "SELECT COUNT(*) AS n FROM users");
            if (((Number) existing.get(0).get("n")).longValue() != 0) return null;
            long alice = insert(c, "INSERT INTO users(username,name,email,password_hash,role) VALUES(?,?,?,?,?)",
                    "alice", "Alice Chen", "alice@example.test", Passwords.hash("AliceDemo1!"), "USER");
            long bob = insert(c, "INSERT INTO users(username,name,email,password_hash,role) VALUES(?,?,?,?,?)",
                    "bob", "Bob Rivera", "bob@example.test", Passwords.hash("BobDemo1!"), "USER");
            insert(c, "INSERT INTO users(username,name,email,password_hash,role) VALUES(?,?,?,?,?)",
                    "admin", "Demo Administrator", "admin@example.test", Passwords.hash("AdminDemo1!"), "ADMIN");
            LocalDate today = LocalDate.now(ZoneId.of("America/Los_Angeles"));
            report(c,bob,"FOUND","PUBLIC","Silver wireless headphones",
                    "Silver wireless headphones in a gray travel case. Found near the study tables.",
                    "ELECTRONICS","HEADPHONES","Silver","",today.minusDays(1),"Leavey Library", "", "Describe a distinctive mark on the outside of the case.","blue triangle sticker");
            report(c,bob,"FOUND","PUBLIC","Canvas tote bag",
                    "Cream canvas tote bag with green lettering and long handles. Found on a bench.",
                    "BAGS","TOTE","Cream","",today.minusDays(1),"Trousdale Parkway", "", "What is the exact wording printed on the outside?","read more books");
            report(c,alice,"FOUND","PUBLIC","Navy varsity jacket",
                    "Navy jacket with cream sleeves and snap buttons. Found after an afternoon lecture.",
                    "CLOTHING","JACKET","Navy","",today.minusDays(2),"Taper Hall", "", "Describe the visible initials on the cuff.","AC");
            report(c,bob,"FOUND","PRIVATE","Black canvas backpack",
                    "Black canvas backpack with a silver zipper and red front loop. Found by a library study desk.",
                    "BAGS","BACKPACK","Black","",today,"Leavey Library", "Handled only by the exterior; contents not inspected.", "Describe the distinct exterior patch near the top handle.","small yellow moon");
            report(c,alice,"LOST","PRIVATE","Black canvas backpack",
                    "Black canvas backpack with a silver zipper and red front loop. Last seen at a library study desk.",
                    "BAGS","BACKPACK","Black","",today.minusDays(1),"Leavey Library", "Small yellow moon patch near the top handle.","", "");
            report(c,bob,"FOUND","PUBLIC","Blue reusable water bottle",
                    "Blue insulated water bottle with a black lid and metal handle. Found beside the benches.",
                    "OTHER","BOTTLE","Blue","",today,"McCarthy Quad", "", "Describe the visible sticker on the bottle.","orange sunset");
            report(c,alice,"FOUND","PUBLIC","Round metal key ring",
                    "Two silver keys on a round metal key ring. Found on the pavement near the entrance.",
                    "OTHER","KEYS","Silver","",today.minusDays(3),"Doheny Library", "", "What shape is the attached keychain?","green turtle");
            report(c,bob,"FOUND","PRIVATE","Black laptop",
                    "Black laptop with a matte case and a small silver exterior logo. Found on a lobby table.",
                    "ELECTRONICS","LAPTOP","Black","",today.minusDays(1),"Ronald Tutor Hall", "Do not unlock or open the device.", "Describe a distinctive exterior sticker.","purple rocket");
            insert(c,"INSERT INTO notifications(user_id,message,event_key) VALUES(?,?,?)",alice,
                    "Welcome to REUNITE. Your lost reports can reveal matching private found items. A possible match is a lead, not proof of ownership.","welcome:"+alice);
            insert(c,"INSERT INTO notifications(user_id,message,event_key) VALUES(?,?,?)",bob,
                    "Welcome to REUNITE. Private found items appear only as a count in general search.","welcome:"+bob);
            return null;
        });
    }

    private static long report(Connection c,long owner,String type,String visibility,String title,String description,
            String category,String subcategory,String color,String brand,LocalDate date,String location,
            String privateDetails,String question,String answer) {
        long id = insert(c,"INSERT INTO reports(owner_id,type,visibility,title,description,category,subcategory,color,brand,event_date,location) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                owner,type,visibility,title,description,category,subcategory,color,brand,date,location);
        update(c,"INSERT INTO report_secrets(report_id,private_details,verification_question,answer_hash) VALUES(?,?,?,?)",
                id,privateDetails,question,answer.isEmpty() ? "" : Passwords.hash(Passwords.normalizedAnswer(answer)));
        return id;
    }
}
