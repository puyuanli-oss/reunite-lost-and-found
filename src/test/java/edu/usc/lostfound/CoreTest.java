package edu.usc.lostfound;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Focused regression checks for the input boundary and stored authentication evidence. */
public final class CoreTest {
    private static int checks;
    public static void main(String[] args) {
        Map<String,Object> source = new LinkedHashMap<>();
        source.put("text", "Quote: \" Backslash: \\ Unicode: 图书馆\n\t\r\b\f");
        source.put("nothing", null);
        source.put("enabled", true);
        source.put("list", List.of("one", "two"));
        source.put("nested", Map.of("name", "<script>alert(1)</script>"));
        Map<String,Object> restored = Json.object(Json.write(source));
        check(source.equals(restored), "JSON object round trip preserves strings, controls, nulls, arrays and nesting");
        check(Json.object(" { \"n\" : 9007199254740993 } ").get("n").equals(9007199254740993L), "integer parsing preserves IDs above double precision");
        check(Json.object("{\"n\":-42}").get("n").equals(-42L), "integer parsing retains integral type");
        check(((Number)Json.object("{\"n\":1.25e2}").get("n")).doubleValue() == 125, "finite decimal exponent parsing");
        check(Json.object("{\"unicode\":\"\\u0041\\u4e2d\"}").get("unicode").equals("A中"), "JSON Unicode escapes");
        for (String invalid : List.of("[]", "null", "", "{} trailing", "{\"x\":1,\"x\":2}",
                "{\"x\":01}", "{\"x\":+1}", "{\"x\":.5}", "{\"x\":1.}", "{\"x\":1e309}",
                "{\"x\":NaN}", "{\"x\":Infinity}", "{\"x\":true,}", "{\"x\":[1,]}",
                "{\"x\":\"\\q\"}", "{\"x\":\"\\uQQQQ\"}", "{\"x\":\"raw\ncontrol\"}")) {
            rejected(invalid);
        }
        rejected("{\"deep\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}");
        check(Passwords.valid("StrongPassword1!"), "normal password accepted");
        check(!Passwords.valid("short1A"), "short password rejected");
        check(!Passwords.valid("lowercase123"), "missing uppercase rejected");
        check(!Passwords.valid("NoDigitsHere!"), "missing digit rejected");
        check(!Passwords.valid("AAAaa1234!"), "consecutive triple rejected");
        check(Passwords.valid("Aa1a2a3a!"), "repeats in separate positions accepted");
        check(!Passwords.valid("A1" + "x".repeat(127)), "overlong password rejected");
        String first = Passwords.hash("StrongPassword1!"), second = Passwords.hash("StrongPassword1!");
        check(!first.equals(second), "random salt produces different stored hashes");
        check(Passwords.verify("StrongPassword1!", first), "correct password verifies");
        check(!Passwords.verify("strongPassword1!", first), "passwords remain case sensitive");
        check(!Passwords.verify("StrongPassword1! ", first), "password whitespace remains significant");
        for(String malformed : List.of("", "password", "pbkdf2$1$AA==$AA==", "pbkdf2$999999999$AA==$AA==", "pbkdf2$210000$invalid$invalid")) {
            check(!Passwords.verify("anything", malformed), "malformed stored hash fails closed");
        }
        check(Passwords.normalizedAnswer("  ＢＬＵＥ  Triangle\tSticker ").equals("blue triangle sticker"), "answer normalization is consistent");
        String answerHash = Passwords.hash(Passwords.normalizedAnswer(" BLUE  triangle "));
        check(Passwords.verify(Passwords.normalizedAnswer("blue TRIANGLE"), answerHash), "normalized ownership answer verifies");
        Set<String> tokens = new java.util.HashSet<>();
        for(int i=0;i<100;i++) {
            String token=Passwords.token();
            check(token.matches("[A-Za-z0-9_-]{43}") && tokens.add(token), "session tokens are random URL-safe 256-bit values");
        }
        System.out.println("CoreTest: " + checks + " checks passed.");
    }
    private static void rejected(String input) {
        boolean rejected=false;
        try { Json.object(input); } catch (IllegalArgumentException expected) { rejected=true; }
        check(rejected, "malformed or dangerous JSON rejected: " + input.substring(0,Math.min(input.length(),50)));
    }
    private static void check(boolean condition,String message) {
        checks++;
        if(!condition) throw new AssertionError(message);
    }
}
