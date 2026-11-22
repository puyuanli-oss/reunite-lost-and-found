package edu.usc.lostfound;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** Report/photo module. Runs in Eclipse or with the bundled launcher; no servlet container. */
public final class App {
    private final Database db;
    private final ConcurrentHashMap<String,Session> sessions=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Window> limits=new ConcurrentHashMap<>();
    private final String dummyHash=Passwords.hash("TimingDummy1!");
    private static final ZoneId CAMPUS=ZoneId.of("America/Los_Angeles");
    private static final int PAGE_SIZE=12, MAX_JSON=32*1024;
    private static final long MAX_PHOTO=10*1024*1024;
    private static final String SELECT_REPORT="SELECT r.*, CASE WHEN p.report_id IS NULL THEN FALSE ELSE TRUE END AS has_photo FROM reports r LEFT JOIN photos p ON p.report_id=r.id ";
    private static final Map<String,List<String>> CATEGORIES=new LinkedHashMap<>();
    static {
        CATEGORIES.put("BAGS",List.of("BACKPACK","PURSE","TOTE","OTHER"));
        CATEGORIES.put("ELECTRONICS",List.of("LAPTOP","PHONE","HEADPHONES","TABLET","CHARGER","OTHER"));
        CATEGORIES.put("IDS",List.of("USC_ID","DRIVERS_LICENSE","PASSPORT","OTHER"));
        CATEGORIES.put("CLOTHING",List.of("JACKET","SHIRT","HAT","OTHER"));
        CATEGORIES.put("OTHER",List.of("WALLET","KEYS","BOTTLE","JEWELRY","OTHER"));
    }
    private static final class Session {final String csrf=Passwords.token();volatile Long userId;volatile long last=System.currentTimeMillis();}
    private static final class Window {long since=System.currentTimeMillis();int count=0;}
    private static final class Failure extends RuntimeException {final int status;final String code;final Map<String,String> fields;Failure(int status,String code,String message){this(status,code,message,Map.of());}Failure(int status,String code,String message,Map<String,String> fields){super(message);this.status=status;this.code=code;this.fields=fields;}}
    public App(Database db){this.db=db;}
    public static void main(String[] args) throws Exception {
        Database db=new Database();
        if(!"false".equalsIgnoreCase(System.getenv().getOrDefault("DEMO_MODE","true")))db.seed();
        App app=new App(db);int port=Integer.parseInt(System.getenv().getOrDefault("PORT","8080"));
        var server=HttpServer.create(new InetSocketAddress(System.getenv().getOrDefault("BIND_ADDRESS","127.0.0.1"),port),64);
        server.createContext("/",app::handle);server.setExecutor(Executors.newFixedThreadPool(16));
        app.refreshMatches();server.start();System.out.println("REUNITE running at http://localhost:"+port+" (bind "+server.getAddress()+")");
        Runtime.getRuntime().addShutdownHook(new Thread(()->server.stop(1)));
    }
    private void handle(HttpExchange x) throws IOException {
        try {
            headers(x);String path=x.getRequestURI().getPath();
            if(path.startsWith("/api/")) {Object result=api(x,path);if(result!=BYTES)json(x,200,result);}
            else staticFile(x,path);
        } catch(Failure e){json(x,e.status,map("error",e.code,"message",e.getMessage(),"fields",e.fields));}
        catch(IllegalArgumentException e){json(x,400,map("error","VALIDATION","message",e.getMessage()==null?"Invalid input":e.getMessage()));}
        catch(Exception e){String ref=UUID.randomUUID().toString().substring(0,8);System.err.println("Request failed "+ref+": "+e.getClass().getSimpleName());json(x,500,map("error","SERVER_ERROR","message","The operation could not be completed. Reference: "+ref));}
        finally{x.close();}
    }
    private static final Object BYTES=new Object();
    private Object api(HttpExchange x,String path) throws Exception {
        if(!Set.of("GET","POST","PUT","DELETE").contains(x.getRequestMethod()))throw new Failure(405,"METHOD","Method not allowed");
        Session session=session(x);Map<String,Object> user=session.userId==null?null:db.one("SELECT id,username,name,role FROM users WHERE id=?",session.userId);
        if(user==null)session.userId=null;
        boolean write=!x.getRequestMethod().equals("GET");
        if(write){String csrf=x.getRequestHeaders().getFirst("X-CSRF-Token");if(csrf==null||!java.security.MessageDigest.isEqual(csrf.getBytes(StandardCharsets.UTF_8),session.csrf.getBytes(StandardCharsets.UTF_8)))throw new Failure(403,"CSRF","Please reload the page and try again.");}
        if(path.equals("/api/session")&&method(x,"GET"))return map("user",user,"csrfToken",session.csrf,"demoMode",demo());
        if(path.equals("/api/categories")&&method(x,"GET"))return map("categories",CATEGORIES.entrySet().stream().map(e->map("id",e.getKey(),"name",label(e.getKey()),"subcategories",e.getValue().stream().map(s->map("id",s,"name",label(s))).toList())).toList());
        if(path.equals("/api/login")&&method(x,"POST"))return login(x,session);
        if(path.equals("/api/logout")&&method(x,"POST")){session.userId=null;Session fresh=rotate(x,session,null);return map("ok",true,"csrfToken",fresh.csrf);}
        if(path.equals("/api/register")&&method(x,"POST"))return register(x,session);
        if(path.equals("/api/search")&&method(x,"GET"))return search(x,user);
        if(path.equals("/api/me")&&method(x,"GET")){requireUser(user);return dashboard(user);}
        if(path.equals("/api/reports")&&method(x,"POST")){requireUser(user);throttle("create:"+id(user),30,3600_000);return saveReport(x,user,null);}
        if(path.equals("/api/claims")&&method(x,"POST")){requireUser(user);return claim(x,user);}
        if(path.equals("/api/admin")&&method(x,"GET")){requireAdmin(user);return admin();}
        if(path.matches("/api/admin/claims/[0-9]+/decision")&&method(x,"POST")){requireAdmin(user);return decide(x,user,Long.parseLong(path.split("/")[4]));}
        if(path.matches("/api/reports/[0-9]+(?:/photo)?")) {
            long rid=Long.parseLong(path.split("/")[3]);Map<String,Object> r=report(rid);
            if(r==null)throw notFound();boolean photo=path.endsWith("/photo");
            if(method(x,"GET")){
                if(!canView(user,r))throw notFound();
                if(photo){var p=db.one("SELECT content,mime_type FROM photos WHERE report_id=?",rid);if(p==null)throw notFound();byte[] b=(byte[])p.get("content");x.getResponseHeaders().set("Content-Type",str(p,"mimeType"));x.getResponseHeaders().set("Cache-Control","no-store");x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);return BYTES;}
                boolean edit=owns(user,r)&&open(r);String question=null;
                if(user!=null&&str(r,"type").equals("FOUND")&&open(r)&&!owns(user,r)){var s=db.one("SELECT verification_question FROM report_secrets WHERE report_id=?",rid);if(s!=null)question=str(s,"verificationQuestion");}
                Map<String,Object> visible=view(r);if(owns(user,r)||isAdmin(user)){var sec=db.one("SELECT private_details,verification_question FROM report_secrets WHERE report_id=?",rid);if(sec!=null){visible.put("privateDetails",sec.get("privateDetails"));visible.put("verificationQuestion",sec.get("verificationQuestion"));}}
                return map("report",visible,"canEdit",edit,"canClaim",user!=null&&open(r)&&str(r,"type").equals("FOUND")&&!owns(user,r),"verificationQuestion",question);
            }
            requireUser(user);if(!owns(user,r))throw new Failure(403,"FORBIDDEN","Only the report owner can change this report.");if(!open(r))throw new Failure(409,"INACTIVE","Only open reports can be changed.");
            if(photo){if(method(x,"POST"))return uploadPhoto(x,user,r);if(method(x,"DELETE")){int expected=photoVersion(x);db.tx(c->{photoEditable(c,rid,id(user),expected);Database.update(c,"DELETE FROM photos WHERE report_id=?",rid);Database.update(c,"UPDATE reports SET version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",rid);return null;});return map("ok",true,"report",view(report(rid)));}}
            else if(method(x,"PUT"))return saveReport(x,user,r);
            else if(method(x,"DELETE")){
                int version=positiveInt(params(x).getOrDefault("version","0"),0);if(version<1)throw new Failure(400,"VERSION","The report version is required.");
                db.tx(c->{Database.query(c,"SELECT id FROM reports WHERE id=? FOR UPDATE",rid);if(!Database.query(c,"SELECT id FROM claims WHERE found_report_id=? AND status='PENDING'",rid).isEmpty())throw new Failure(409,"CLAIM_PENDING","This report has a pending ownership review.");int updated=Database.update(c,"UPDATE reports SET status='REMOVED',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND owner_id=? AND status='OPEN' AND version=?",rid,id(user),version);if(updated!=1)throw new Failure(409,"STALE","This report changed. Reload before removing it.");return null;});refreshMatches();return map("ok",true);
            }
        }
        throw notFound();
    }
    private Object login(HttpExchange x,Session s)throws Exception {
        var b=body(x);String username=str(b,"username").strip().toLowerCase(Locale.ROOT),password=str(b,"password");
        throttle("login-ip:"+ip(x),20,30*60_000);throttle("login-user:"+username,5,30*60_000);
        var u=db.one("SELECT * FROM users WHERE username=?",username);boolean good=Passwords.verify(password,u==null?dummyHash:str(u,"passwordHash"));
        if(!good||u==null)throw new Failure(401,"CREDENTIALS","Incorrect username or password");
        limits.remove("login-user:"+username);Session fresh=rotate(x,s,id(u));return map("user",publicUser(u),"csrfToken",fresh.csrf);
    }
    private Object register(HttpExchange x,Session s)throws Exception {
        if(!demo())throw new Failure(403,"DEMO_DISABLED","Registration belongs to your verified account service. Demo registration is disabled.");
        throttle("registration:"+ip(x),5,3600_000);var b=body(x);String username=str(b,"username").strip().toLowerCase(Locale.ROOT),name=str(b,"name").strip(),email=str(b,"email").strip().toLowerCase(Locale.ROOT),password=str(b,"password");
        var errors=new LinkedHashMap<String,String>();if(!username.matches("[a-z0-9_]{3,30}"))errors.put("username","Use 3–30 letters, numbers, or underscores.");if(name.isEmpty()||name.length()>100)errors.put("name","Enter a name up to 100 characters.");if(email.length()>254||!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))errors.put("email","Enter a valid email.");if(!Passwords.valid(password))errors.put("password","Use 8–128 characters, an uppercase letter and a number; no three repeated characters.");
        if(!errors.isEmpty())throw new Failure(400,"VALIDATION","Check the highlighted fields.",errors);
        if(db.one("SELECT id FROM users WHERE username=? OR email=?",username,email)!=null)throw new Failure(409,"DUPLICATE","Username or email is already in use.");
        long uid=db.insert("INSERT INTO users(username,name,email,password_hash,role) VALUES(?,?,?,?, 'USER')",username,name,email,Passwords.hash(password));Session fresh=rotate(x,s,uid);return map("csrfToken",fresh.csrf,"user",publicUser(db.one("SELECT * FROM users WHERE id=?",uid)),"message","Local demo account created. Email delivery is not connected.");
    }
    private Object search(HttpExchange x,Map<String,Object> user) {
        var p=params(x);throttle("search:"+(user==null?ip(x):id(user)),60,60_000);
        String query=p.getOrDefault("q","").strip(),category=p.getOrDefault("category",""),type=p.getOrDefault("type",""),location=p.getOrDefault("location","").strip();
        if(query.length()>200||location.length()>160)throw new Failure(400,"VALIDATION","Search text is too long.");
        if(!category.isEmpty()&&!CATEGORIES.containsKey(category))throw new Failure(400,"VALIDATION","Unknown category.");
        if(!type.isEmpty()&&!Set.of("LOST","FOUND").contains(type))throw new Failure(400,"VALIDATION","Unknown report type.");
        LocalDate from=dateFilter(p.get("from")),to=dateFilter(p.get("to"));if(from!=null&&to!=null&&from.isAfter(to))throw new Failure(400,"VALIDATION","The start date must be before the end date.");
        Set<String> words=MatchingEngine.tokens(query);var publicRows=new ArrayList<Map<String,Object>>();int privateCount=0;
        for(var r:db.query(SELECT_REPORT+"WHERE r.status='OPEN'")){
            if(!category.isEmpty()&&!category.equals(str(r,"category")))continue;if(!type.isEmpty()&&!type.equals(str(r,"type")))continue;
            if(!location.isEmpty()&&!str(r,"location").toLowerCase(Locale.ROOT).contains(location.toLowerCase(Locale.ROOT)))continue;
            LocalDate event=LocalDate.parse(str(r,"eventDate"));if(from!=null&&event.isBefore(from)||to!=null&&event.isAfter(to))continue;
            Set<String> content=MatchingEngine.tokens(str(r,"title")+" "+str(r,"description"));if(!words.isEmpty()&&!content.containsAll(words))continue;
            // A nonempty stop-word-only query must not silently broaden into an unfiltered result.
            if(!query.isEmpty()&&words.isEmpty()&&!(str(r,"title")+" "+str(r,"description")).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))continue;
            if(str(r,"visibility").equals("PUBLIC"))publicRows.add(r);else if(str(r,"type").equals("FOUND"))privateCount++;
        }
        publicRows.sort(Comparator.<Map<String,Object>>comparingInt(r->relevance(r,query)).reversed().thenComparing(r->str(r,"eventDate"),Comparator.reverseOrder()).thenComparing(r->num(r.get("id")),Comparator.reverseOrder()));
        int page=positiveInt(p.getOrDefault("page","1"),1),total=publicRows.size(),start=(int)Math.min((long)(page-1)*PAGE_SIZE,total);
        boolean useful=!query.isEmpty()||!category.isEmpty()||!location.isEmpty()||from!=null||to!=null;
        return map("reports",publicRows.subList(start,Math.min(start+PAGE_SIZE,total)).stream().map(App::view).toList(),"total",total,"page",page,"pageSize",PAGE_SIZE,"privateCount",user!=null&&useful?privateCount:null,"privateCountMessage",user==null?"Sign in to see how many private found reports match.":!useful?"Add a keyword or filter to count matching private found reports.":"Only a count is shown. Submit a detailed lost report to unlock eligible matches.");
    }
    private static int relevance(Map<String,Object> r,String q){if(q.isBlank())return 0;String title=str(r,"title").toLowerCase(Locale.ROOT);return (title.contains(q.toLowerCase(Locale.ROOT))?10:0)+MatchingEngine.tokens(q).stream().mapToInt(t->MatchingEngine.tokens(title).contains(t)?2:1).sum();}
    private Object saveReport(HttpExchange x,Map<String,Object> user,Map<String,Object> existing)throws Exception {
        var b=body(x);var e=new LinkedHashMap<String,String>();String type=str(b,"type").toUpperCase(Locale.ROOT),visibility=str(b,"visibility").toUpperCase(Locale.ROOT);
        if(!Set.of("LOST","FOUND").contains(type))e.put("type","Choose lost or found.");if(!Set.of("PUBLIC","PRIVATE").contains(visibility))e.put("visibility","Choose public or private.");if(type.equals("LOST"))visibility="PRIVATE";
        if(existing!=null&&!str(existing,"type").equals(type))e.put("type","Report type cannot be changed.");
        String title=field(b,"title",3,120,e),description=field(b,"description",10,3000,e),location=field(b,"location",2,160,e),category=str(b,"category"),sub=str(b,"subcategory");
        if(!CATEGORIES.containsKey(category))e.put("category","Choose an item category.");else if(!sub.isEmpty()&&!CATEGORIES.get(category).contains(sub))e.put("subcategory","Choose a subcategory belonging to this category.");
        String color=field(b,"color",0,40,e),brand=field(b,"brand",0,60,e),details=field(b,"privateDetails",0,2000,e),question=field(b,"verificationQuestion",0,300,e),answer=field(b,"verificationAnswer",0,300,e);
        String event=str(b,"eventDate");try{LocalDate d=LocalDate.parse(event),today=LocalDate.now(CAMPUS);if(d.isAfter(today)||d.isBefore(today.minusYears(5)))e.put("eventDate","Choose a date within the past five years.");}catch(Exception ex){e.put("eventDate","Choose a valid date.");}
        boolean preserveQuestion=false;if(existing!=null&&answer.isBlank()){var currentSecret=db.one("SELECT verification_question FROM report_secrets WHERE report_id=?",id(existing));preserveQuestion=question.isBlank()||currentSecret!=null&&question.equals(str(currentSecret,"verificationQuestion"));}
        if(type.equals("FOUND")&&!preserveQuestion&&(existing==null||!question.isBlank()||!answer.isBlank())){if(question.length()<8)e.put("verificationQuestion","Use a question of at least 8 characters.");if(answer.length()<2)e.put("verificationAnswer","Enter the private expected answer.");}
        if(!e.isEmpty())throw new Failure(400,"VALIDATION","Check the highlighted fields.",e);
        final String vis=visibility;final String answerHash=answer.isEmpty()?null:Passwords.hash(Passwords.normalizedAnswer(answer));
        long rid=db.tx(c->{
            long result;
            if(existing==null){result=Database.insert(c,"INSERT INTO reports(owner_id,type,visibility,title,description,category,subcategory,color,brand,event_date,location,status,version) VALUES(?,?,?,?,?,?,?,?,?,?,?,'OPEN',1)",id(user),type,vis,title,description,category,sub,color,brand,event,location);Database.update(c,"INSERT INTO report_secrets(report_id,private_details,verification_question,answer_hash) VALUES(?,?,?,?)",result,details,question,answerHash==null?"":answerHash);}
            else {
                result=id(existing);var lock=Database.query(c,"SELECT id FROM reports WHERE id=? FOR UPDATE",result);if(lock.isEmpty())throw notFound();
                if(!Database.query(c,"SELECT id FROM claims WHERE found_report_id=? AND status='PENDING'",result).isEmpty())throw new Failure(409,"CLAIM_PENDING","This report has a pending ownership review. Please wait before editing.");
                int version=(int)num(b.get("version"));int updated=Database.update(c,"UPDATE reports SET visibility=?,title=?,description=?,category=?,subcategory=?,color=?,brand=?,event_date=?,location=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND owner_id=? AND version=? AND status='OPEN'",vis,title,description,category,sub,color,brand,event,location,result,id(user),version);
                if(updated!=1)throw new Failure(409,"STALE","This report changed. Reload before editing.");
                if(answerHash==null)Database.update(c,"UPDATE report_secrets SET private_details=? WHERE report_id=?",details,result);else Database.update(c,"UPDATE report_secrets SET private_details=?,verification_question=?,answer_hash=? WHERE report_id=?",details,question,answerHash,result);
            }
            return result;
        });
        refreshMatches();return map("report",view(report(rid)));
    }
    private Object uploadPhoto(HttpExchange x,Map<String,Object> user,Map<String,Object> r)throws Exception {
        String declared=x.getRequestHeaders().getFirst("Content-Type");byte[] raw=readLimited(x,(int)MAX_PHOTO);
        PhotoService.Sanitized photo=PhotoService.sanitize(raw,declared);int expected=photoVersion(x);
        db.tx(c->{photoEditable(c,id(r),id(user),expected);Database.update(c,"DELETE FROM photos WHERE report_id=?",id(r));Database.update(c,"INSERT INTO photos(report_id,mime_type,content,size_bytes) VALUES(?,?,?,?)",id(r),photo.mimeType(),photo.bytes(),photo.bytes().length);Database.update(c,"UPDATE reports SET version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",id(r));return null;});
        return map("ok",true,"report",view(report(id(r))));
    }
    private static int photoVersion(HttpExchange x){int expected=positiveInt(x.getRequestHeaders().getFirst("X-Report-Version"),0);if(expected<1)throw new Failure(400,"VERSION","The current report version is required for photo changes.");return expected;}
    private static void photoEditable(Connection c,long rid,long uid,int version){var rows=Database.query(c,"SELECT owner_id,status,version FROM reports WHERE id=? FOR UPDATE",rid);if(rows.isEmpty()||num(rows.get(0).get("ownerId"))!=uid||!str(rows.get(0),"status").equals("OPEN"))throw new Failure(409,"INACTIVE","The report is no longer editable.");if(num(rows.get(0).get("version"))!=version)throw new Failure(409,"STALE","This report changed. Reload before changing its photo.");if(!Database.query(c,"SELECT id FROM claims WHERE found_report_id=? AND status='PENDING'",rid).isEmpty())throw new Failure(409,"CLAIM_PENDING","An ownership review is pending. Photo changes are temporarily paused.");}
    private synchronized void refreshMatches() {
        var all=db.query("SELECT * FROM reports WHERE status='OPEN'");var lost=all.stream().filter(r->str(r,"type").equals("LOST")).toList();var found=all.stream().filter(r->str(r,"type").equals("FOUND")).toList();
        db.tx(c->{Database.update(c,"UPDATE matches SET status='WITHDRAWN',updated_at=CURRENT_TIMESTAMP WHERE status='ACTIVE'");for(var l:lost)for(var f:found){var d=MatchingEngine.compare(l,f);if(!d.eligible())continue;var pair=Database.query(c,"SELECT id FROM matches WHERE lost_report_id=? AND found_report_id=?",id(l),id(f));if(pair.isEmpty()){Database.insert(c,"INSERT INTO matches(lost_report_id,found_report_id,score,status) VALUES(?,?,?,'ACTIVE')",id(l),id(f),d.score());notify(c,num(l.get("ownerId")),"match:"+id(l)+":"+id(f)+":"+num(l.get("ownerId")),"A possible match is available in My reports. Matching does not establish ownership.");notify(c,num(f.get("ownerId")),"match:"+id(l)+":"+id(f)+":"+num(f.get("ownerId")),"A lost report may match one of your found reports. An administrator must review any claim.");}else Database.update(c,"UPDATE matches SET score=?,status='ACTIVE',updated_at=CURRENT_TIMESTAMP WHERE id=?",d.score(),id(pair.get(0)));}return null;});
    }
    private boolean canView(Map<String,Object> u,Map<String,Object> r){
        if(owns(u,r)||isAdmin(u))return true;if(!open(r))return false;if(str(r,"visibility").equals("PUBLIC"))return true;
        if(u==null||!str(r,"type").equals("FOUND"))return false;
        for(var l:db.query("SELECT * FROM reports WHERE owner_id=? AND type='LOST' AND status='OPEN'",id(u)))if(MatchingEngine.compare(l,r).eligible())return true;
        return false;
    }
    private Object dashboard(Map<String,Object> u){
        var own=db.query(SELECT_REPORT+"WHERE r.owner_id=? ORDER BY r.created_at DESC,r.id DESC",id(u));var matches=new ArrayList<Map<String,Object>>();
        for(var l:own)if(str(l,"type").equals("LOST")&&open(l))for(var f:db.query(SELECT_REPORT+"WHERE r.type='FOUND' AND r.status='OPEN'")){var d=MatchingEngine.compare(l,f);if(d.eligible())matches.add(map("lostReportId",id(l),"foundReport",view(f),"score",d.score(),"reasons",d.reasons()));}
        matches.sort(Comparator.comparingDouble((Map<String,Object> m)->((Number)m.get("score")).doubleValue()).reversed());
        return map("reports",own.stream().map(App::view).toList(),"matches",matches,"claims",db.query("SELECT id,found_report_id,lost_report_id,status,explanation,created_at,decided_at,reason FROM claims WHERE claimant_id=? ORDER BY created_at DESC",id(u)),"notifications",db.query("SELECT id,message,created_at FROM notifications WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 100",id(u)));
    }
    private Object claim(HttpExchange x,Map<String,Object> u)throws Exception {
        var b=body(x);long foundId=num(b.get("foundReportId")),lostId=num(b.get("lostReportId"));String answer=str(b,"answer").strip(),explanation=str(b,"explanation").strip();
        if(answer.length()<2||answer.length()>300||explanation.length()>2000)throw new Failure(400,"VALIDATION","Enter a verification answer up to 300 characters and supporting information up to 2000 characters.");
        var found=report(foundId);if(found==null||!canView(u,found))throw notFound();if(owns(u,found)||!open(found)||!str(found,"type").equals("FOUND"))throw new Failure(409,"INELIGIBLE","This item cannot be claimed.");
        if(lostId>0){var lost=report(lostId);if(lost==null||!owns(u,lost)||!MatchingEngine.compare(lost,found).eligible())throw new Failure(403,"LOST_REPORT","Choose your own active matching lost report.");}
        if(str(found,"visibility").equals("PRIVATE")&&lostId==0) {for(var l:db.query("SELECT * FROM reports WHERE owner_id=? AND type='LOST' AND status='OPEN'",id(u)))if(MatchingEngine.compare(l,found).eligible()){lostId=id(l);break;}if(lostId==0)throw notFound();}
        final Long linked=lostId>0?lostId:null;final String normalized=Passwords.normalizedAnswer(answer);final String hash=Passwords.hash(normalized);
        long cid=db.tx(c->{
            var locked=Database.query(c,"SELECT * FROM reports WHERE id=? FOR UPDATE",foundId);if(locked.isEmpty()||!open(locked.get(0)))throw new Failure(409,"INACTIVE","This item is no longer available.");
            var currentFound=locked.get(0);boolean currentMatch=false;if(linked!=null){var lr=Database.query(c,"SELECT * FROM reports WHERE id=? FOR UPDATE",linked);currentMatch=!lr.isEmpty()&&owns(u,lr.get(0))&&MatchingEngine.compare(lr.get(0),currentFound).eligible();if(!currentMatch)throw new Failure(409,"MATCH_CHANGED","Your lost report no longer matches this found report.");}if(str(currentFound,"visibility").equals("PRIVATE")&&!currentMatch)throw notFound();
            // Owner-row lock serializes all claim attempts for this item, including attempt counting.
            var pending=Database.query(c,"SELECT id FROM claims WHERE found_report_id=? AND claimant_id=? AND status='PENDING'",foundId,id(u));if(!pending.isEmpty())throw new Failure(409,"PENDING","You already have a pending claim for this item.");
            var attempts=Database.query(c,"SELECT id FROM claims WHERE found_report_id=? AND claimant_id=? AND created_at>=?",foundId,id(u),Timestamp.from(Instant.now().minus(Duration.ofHours(24))));if(attempts.size()>=3)throw new Failure(429,"ATTEMPTS","Only three claim attempts per item are allowed in 24 hours.");
            var secret=Database.query(c,"SELECT answer_hash FROM report_secrets WHERE report_id=?",foundId);boolean answerMatches=!secret.isEmpty()&&Passwords.verify(normalized,str(secret.get(0),"answerHash"));
            long result=Database.insert(c,"INSERT INTO claims(found_report_id,lost_report_id,claimant_id,answer_hash,answer_matches,explanation,status) VALUES(?,?,?,?,?,?,'PENDING')",foundId,linked,id(u),hash,answerMatches,explanation);
            notify(c,num(found.get("ownerId")),"claim:"+result,"An ownership claim was submitted for one of your found reports.");return result;
        });return map("id",cid,"status","PENDING","message","Claim submitted for administrator review. A matching score does not prove ownership.");
    }
    private Object admin(){
        // Answers and private notes are deliberately excluded from this overview.
        var claims=db.query("SELECT c.id,c.found_report_id,c.lost_report_id,c.claimant_id,c.answer_matches,c.explanation,c.status,c.created_at,c.reason,r.title FROM claims c JOIN reports r ON r.id=c.found_report_id ORDER BY c.created_at,c.id");
        for(var c:claims){Object result=c.get("answerMatches");c.put("answerMatches",Boolean.TRUE.equals(result)||result instanceof Number n&&n.intValue()!=0);}
        return map("reports",db.query(SELECT_REPORT+"ORDER BY r.id DESC LIMIT 500").stream().map(App::view).toList(),"claims",claims,"users",db.query("SELECT id,username,name,role FROM users ORDER BY id"),"audit",db.query("SELECT id,actor_id,action,report_id,reason,created_at FROM audit ORDER BY id DESC LIMIT 100"));
    }
    private Object decide(HttpExchange x,Map<String,Object> u,long cid)throws Exception {
        var b=body(x);String decision=str(b,"decision"),reason=str(b,"reason").strip();if(!Set.of("APPROVED","REJECTED").contains(decision)||reason.length()<5||reason.length()>500)throw new Failure(400,"VALIDATION","Choose a decision and record a reason of 5–500 characters.");
        db.tx(c->{var rows=Database.query(c,"SELECT * FROM claims WHERE id=?",cid);if(rows.isEmpty())throw notFound();var claim=rows.get(0);long fid=num(claim.get("foundReportId"));Database.query(c,"SELECT id FROM reports WHERE id=? FOR UPDATE",fid);rows=Database.query(c,"SELECT * FROM claims WHERE id=? FOR UPDATE",cid);claim=rows.get(0);if(!str(claim,"status").equals("PENDING"))throw new Failure(409,"PROCESSED","This claim was already processed.");if(num(claim.get("claimantId"))==id(u))throw new Failure(403,"SELF_REVIEW","Administrators cannot decide their own ownership claims.");
            if(decision.equals("APPROVED")){
                var available=Database.query(c,"SELECT id FROM reports WHERE id=? AND status='OPEN'",fid);if(available.isEmpty())throw new Failure(409,"INACTIVE","The found report is no longer open.");
                if(claim.get("lostReportId")!=null){var currentLost=Database.query(c,"SELECT * FROM reports WHERE id=? FOR UPDATE",num(claim.get("lostReportId")));var currentFound=Database.query(c,"SELECT * FROM reports WHERE id=?",fid);if(currentLost.isEmpty()||num(currentLost.get(0).get("ownerId"))!=num(claim.get("claimantId"))||!MatchingEngine.compare(currentLost.get(0),currentFound.get(0)).eligible())throw new Failure(409,"MATCH_CHANGED","The linked lost report changed. Reassess or reject this claim before proceeding.");}
                Database.update(c,"UPDATE claims SET status='APPROVED',approved_report_id=?,decided_by=?,decided_at=CURRENT_TIMESTAMP,reason=? WHERE id=?",fid,id(u),reason,cid);
                Database.update(c,"UPDATE reports SET status='RESOLVED',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",fid);
                if(claim.get("lostReportId")!=null)Database.update(c,"UPDATE reports SET status='RESOLVED',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND owner_id=? AND status='OPEN'",num(claim.get("lostReportId")),num(claim.get("claimantId")));
                var competing=Database.query(c,"SELECT id,claimant_id FROM claims WHERE found_report_id=? AND status='PENDING'",fid);for(var other:competing)notify(c,num(other.get("claimantId")),"decision:"+id(other),"Your ownership claim was closed after another claim was approved.");
                Database.update(c,"UPDATE claims SET status='REJECTED',reason='Another claim was approved',decided_by=?,decided_at=CURRENT_TIMESTAMP WHERE found_report_id=? AND status='PENDING'",id(u),fid);
            }else Database.update(c,"UPDATE claims SET status='REJECTED',decided_by=?,decided_at=CURRENT_TIMESTAMP,reason=? WHERE id=?",id(u),reason,cid);
            Database.insert(c,"INSERT INTO audit(actor_id,action,report_id,reason) VALUES(?,?,?,?)",id(u),"CLAIM_"+decision,fid,reason);
            notify(c,num(claim.get("claimantId")),"decision:"+cid,"Your ownership claim was "+decision.toLowerCase(Locale.ROOT)+". Check My reports for the review note.");return null;
        });refreshMatches();return map("ok",true);
    }
    private static void notify(Connection c,long uid,String key,String message)throws Exception {if(Database.query(c,"SELECT id FROM notifications WHERE event_key=?",key).isEmpty())Database.insert(c,"INSERT INTO notifications(user_id,event_key,message) VALUES(?,?,?)",uid,key,message);}
    private Map<String,Object> report(long rid){return db.one(SELECT_REPORT+"WHERE r.id=?",rid);}
    private Session session(HttpExchange x){
        String token=null,cookie=x.getRequestHeaders().getFirst("Cookie");if(cookie!=null)for(String part:cookie.split(";")){String[] p=part.strip().split("=",2);if(p.length==2&&p[0].equals("LFSESSION"))token=p[1];}
        Session s=token==null?null:sessions.get(token);long now=System.currentTimeMillis();if(s!=null&&now-s.last>30*60_000){sessions.remove(token);s=null;}
        if(s==null){if(sessions.size()>10000)sessions.entrySet().removeIf(e->now-e.getValue().last>30*60_000);if(sessions.size()>12000)throw new Failure(503,"BUSY","Please try again shortly.");s=new Session();token=Passwords.token();sessions.put(token,s);setCookie(x,token);}s.last=now;return s;
    }
    private Session rotate(HttpExchange x,Session previous,Long uid){sessions.entrySet().removeIf(e->e.getValue()==previous);Session fresh=new Session();fresh.userId=uid;String token=Passwords.token();sessions.put(token,fresh);setCookie(x,token);return fresh;}
    private static void setCookie(HttpExchange x,String token){x.getResponseHeaders().add("Set-Cookie","LFSESSION="+token+"; Path=/; HttpOnly; SameSite=Strict"+("true".equalsIgnoreCase(System.getenv("COOKIE_SECURE"))?"; Secure":""));}
    private void throttle(String key,int max,long millis){long now=System.currentTimeMillis();if(limits.size()>20000)limits.entrySet().removeIf(e->now-e.getValue().since>3600_000);Window w=limits.computeIfAbsent(key,k->new Window());synchronized(w){if(now-w.since>millis){w.since=now;w.count=0;}if(++w.count>max)throw new Failure(429,"RATE_LIMIT","Too many attempts. Please try again later.");}}
    private static String ip(HttpExchange x){return x.getRemoteAddress().getAddress().getHostAddress();}
    private static void requireUser(Map<String,Object> u){if(u==null)throw new Failure(401,"AUTH_REQUIRED","Sign in to continue.");}
    private static void requireAdmin(Map<String,Object> u){requireUser(u);if(!isAdmin(u))throw new Failure(403,"FORBIDDEN","Administrator access is required.");}
    private static boolean isAdmin(Map<String,Object> u){return u!=null&&str(u,"role").equals("ADMIN");}
    private static boolean owns(Map<String,Object> u,Map<String,Object> r){return u!=null&&id(u)==num(r.get("ownerId"));}
    private static boolean open(Map<String,Object> r){return str(r,"status").equals("OPEN");}
    private static Map<String,Object> publicUser(Map<String,Object> u){return map("id",u.get("id"),"username",u.get("username"),"name",u.get("name"),"role",u.get("role"));}
    private static Map<String,Object> view(Map<String,Object> r){var v=new LinkedHashMap<String,Object>();for(String k:List.of("id","type","visibility","title","description","category","subcategory","color","brand","eventDate","location","status","version","createdAt","updatedAt","hasPhoto"))v.put(k,r.get(k));Object has=v.get("hasPhoto");v.put("hasPhoto",Boolean.TRUE.equals(has)||has instanceof Number n&&n.intValue()!=0);return v;}
    private static Map<String,Object> body(HttpExchange x)throws IOException {String ct=x.getRequestHeaders().getFirst("Content-Type");if(ct==null||!ct.toLowerCase(Locale.ROOT).startsWith("application/json"))throw new Failure(415,"CONTENT_TYPE","Send JSON with Content-Type application/json.");return Json.object(new String(readLimited(x,MAX_JSON),StandardCharsets.UTF_8));}
    private static byte[] readLimited(HttpExchange x,int limit)throws IOException {String size=x.getRequestHeaders().getFirst("Content-Length");if(size!=null)try{if(Long.parseLong(size)>limit)throw new Failure(413,"TOO_LARGE","The uploaded content is too large.");}catch(NumberFormatException e){throw new Failure(400,"LENGTH","Invalid content length.");}byte[] b=x.getRequestBody().readNBytes(limit+1);if(b.length>limit)throw new Failure(413,"TOO_LARGE","The uploaded content is too large.");return b;}
    private static Map<String,String> params(HttpExchange x){var p=new LinkedHashMap<String,String>();String q=x.getRequestURI().getRawQuery();if(q!=null)for(String part:q.split("&")){String[] kv=part.split("=",2);p.put(URLDecoder.decode(kv[0],StandardCharsets.UTF_8),kv.length>1?URLDecoder.decode(kv[1],StandardCharsets.UTF_8):"");}return p;}
    private static LocalDate dateFilter(String s){return s==null||s.isEmpty()?null:LocalDate.parse(s);}
    private static int positiveInt(String s,int fallback){try{int v=Integer.parseInt(s);return v>0&&v<100000?v:fallback;}catch(Exception e){return fallback;}}
    private static String field(Map<String,Object>b,String key,int min,int max,Map<String,String>errors){String s=str(b,key).strip();if(s.length()<min||s.length()>max)errors.put(key,min>0?"Enter "+min+"–"+max+" characters.":"Use at most "+max+" characters.");return s;}
    private static boolean method(HttpExchange x,String m){return x.getRequestMethod().equals(m);}
    private static Failure notFound(){return new Failure(404,"NOT_FOUND","Report not found or unavailable.");}
    private static long id(Map<String,Object> r){return num(r.get("id"));}
    private static long num(Object o){if(o==null)return 0;if(o instanceof Number n)return n.longValue();try{return Long.parseLong(o.toString());}catch(Exception e){return 0;}}
    private static String str(Map<String,?> r,String k){Object v=r.get(k);return v==null?"":v.toString();}
    private static boolean demo(){return !"false".equalsIgnoreCase(System.getenv().getOrDefault("DEMO_MODE","true"));}
    private static String label(String s){return switch(s){case "IDS"->"IDs";case "USC_ID"->"USC ID";case "DRIVERS_LICENSE"->"Driver’s license";case "OTHER"->"Other / unsure";default->Arrays.stream(s.toLowerCase(Locale.ROOT).split("_")).map(w->Character.toUpperCase(w.charAt(0))+w.substring(1)).collect(Collectors.joining(" "));};}
    private static Map<String,Object> map(Object...pairs){var m=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
    private static void headers(HttpExchange x){var h=x.getResponseHeaders();h.set("X-Content-Type-Options","nosniff");h.set("Referrer-Policy","no-referrer");h.set("X-Frame-Options","DENY");h.set("Cache-Control","no-store");h.set("Content-Security-Policy","default-src 'self'; img-src 'self' blob: data:; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");h.set("Permissions-Policy","camera=(), microphone=(), geolocation=()");}
    private static void json(HttpExchange x,int status,Object obj)throws IOException {byte[] bytes=Json.write(obj).getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);}
    private static void staticFile(HttpExchange x,String path)throws IOException {if(!method(x,"GET"))throw new Failure(405,"METHOD","Method not allowed");String asset=switch(path){case "/","/index.html"->"index.html";case "/app.js"->"app.js";case "/styles.css"->"styles.css";case "/favicon.svg"->"favicon.svg";default->null;};if(asset==null)throw notFound();try(InputStream in=App.class.getResourceAsStream("/web/"+asset)){if(in==null)throw notFound();byte[] b=in.readAllBytes();x.getResponseHeaders().set("Content-Type",asset.endsWith(".html")?"text/html; charset=utf-8":asset.endsWith(".css")?"text/css; charset=utf-8":asset.endsWith(".svg")?"image/svg+xml":"text/javascript; charset=utf-8");x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);}}
}
