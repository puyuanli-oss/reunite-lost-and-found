package edu.usc.lostfound;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Run with assertions-independent checks so a missing -ea cannot hide failures. */
public final class MatchingEngineTest {
    private static int checks;

    public static void main(String[] args) {
        Map<String, Object> lost = report("LOST", 1);
        Map<String, Object> found = report("FOUND", 2);
        MatchingEngine.Decision perfect = MatchingEngine.compare(lost, found);
        check(perfect.eligible() && perfect.score() == 100, "identical evidence scores 100");
        check(MatchingEngine.compare(found, lost).score() == 0, "direction/type veto");
        check(!MatchingEngine.compare(null, found).eligible(), "missing report fails safely");
        check(!MatchingEngine.compare(lost, changed(found, "status", "RESOLVED")).eligible(), "resolved item veto");
        check(!MatchingEngine.compare(lost, changed(found, "status", "REMOVED")).eligible(), "removed item veto");
        check(!MatchingEngine.compare(lost, changed(found, "ownerId", 1)).eligible(), "same-owner veto");
        check(!MatchingEngine.compare(lost, changed(found, "ownerId", "1")).eligible(), "owner IDs normalize numeric representations");
        check(!MatchingEngine.compare(lost, changed(found, "eventDate", "2026-11-19")).eligible(), "found-before-lost veto");
        check(!MatchingEngine.compare(lost, changed(found, "eventDate", "2026-02-30")).eligible(), "invalid calendar date veto");
        check(!MatchingEngine.compare(lost, changed(found, "eventDate", "")).eligible(), "missing date veto");
        check(!MatchingEngine.compare(lost, changed(found, "category", "IDS")).eligible(), "incompatible known categories veto despite perfect words");
        check(!MatchingEngine.compare(lost, changed(found, "subcategory", "PURSE")).eligible(), "incompatible known subtypes veto");
        check(MatchingEngine.compare(lost, changed(found, "subcategory", "OTHER")).eligible(), "unknown subtype is not a veto");
        check(MatchingEngine.compare(lost, changed(found, "category", "OTHER")).eligible(), "unknown umbrella is not a veto");
        check(MatchingEngine.compare(lost, changed(found, "color", "red")).eligible(), "color disagreement is soft");
        check(MatchingEngine.compare(lost, changed(found, "brand", "Unknown")).score() == 98, "unknown brand earns no points");
        check(MatchingEngine.compare(lost, changed(found, "location", "")).score() == 90, "missing location earns no points");
        check(MatchingEngine.compare(lost, changed(found, "eventDate", "2026-12-04")).score() == 95, "date evidence halves at 14 days");
        check(MatchingEngine.tokens("The BLACK wallets, Wallet, and items found near me!").equals(Set.of("black", "wallet")), "case/plurals/filler normalization");
        check(MatchingEngine.tokens("  ＢＬＡＣＫ  ＷＡＬＬＥＴＳ ").equals(Set.of("black", "wallet")), "Unicode compatibility normalization");
        check(MatchingEngine.tokens(null).isEmpty(), "null tokenization");
        check(MatchingEngine.tokens("a I on the found item report").isEmpty(), "empty meaningful text");
        Map<String, Object> disjoint = changed(changed(found, "title", "Purple umbrella"), "description", "Striped fabric curved wooden handle");
        check(!MatchingEngine.compare(lost, disjoint).eligible(), "no shared description cannot pass on other attributes");
        Map<String, Object> sparseLost = changed(changed(changed(lost, "category", "OTHER"), "subcategory", "OTHER"), "location", "");
        Map<String, Object> sparseFound = changed(changed(changed(found, "category", "OTHER"), "subcategory", "OTHER"), "location", "");
        check(!MatchingEngine.compare(sparseLost, sparseFound).eligible(), "sparse evidence cannot be renormalized to pass");
        check(MatchingEngine.compare(sparseLost, sparseFound).score() == 40, "missing category/subtype/location keep fixed denominator");
        Map<String, Object> singleLost = changed(changed(changed(lost, "subcategory", "OTHER"), "title", "black"), "description", "black");
        Map<String, Object> singleFound = changed(changed(changed(found, "subcategory", "OTHER"), "title", "black"), "description", "black");
        check(MatchingEngine.compare(singleLost, singleFound).score() >= 65 && !MatchingEngine.compare(singleLost, singleFound).eligible(), "single generic shared token without reliable subtype fails");
        check(MatchingEngine.compare(changed(singleLost, "subcategory", "BACKPACK"), changed(singleFound, "subcategory", "BACKPACK")).eligible(), "exact reliable subtype can support one shared descriptor");
        Map<String, Object> typeOnlyLost = changed(changed(lost, "title", "backpack"), "description", "backpack");
        Map<String, Object> typeOnlyFound = changed(changed(found, "title", "backpack"), "description", "backpack");
        check(!MatchingEngine.compare(typeOnlyLost, typeOnlyFound).eligible(), "type-only wording cannot unlock a private item");
        check(MatchingEngine.compare(lost, found).equals(MatchingEngine.compare(lost, found)), "deterministic scoring and explanation");
        check(MatchingEngine.compare(lost, changed(found, "privateDetails", "TOPSECRET")) .equals(perfect), "private details never affect score or explanation");
        check(MatchingEngine.compare(lost, changed(found, "verificationAnswer", "TOPSECRET")).equals(perfect), "verification answer never affects discovery");
        check(MatchingEngine.compare(lost, found, 100).eligible(), "higher threshold supported");
        boolean rejected = false;
        try { MatchingEngine.compare(lost, found, 64.99); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "privacy baseline cannot be lowered through overload");
        for (int days = 0; days < 730; days += 3) {
            double score = MatchingEngine.compare(lost, changed(found, "eventDate", java.time.LocalDate.of(2026, 11, 20).plusDays(days).toString())).score();
            check(Double.isFinite(score) && score >= 0 && score <= 100, "bounded score at day " + days);
        }
        System.out.println("MatchingEngineTest: " + checks + " checks passed.");
    }

    private static Map<String, Object> report(String type, long owner) {
        Map<String, Object> row = new HashMap<>();
        row.put("ownerId", owner);
        row.put("type", type);
        row.put("status", "OPEN");
        row.put("category", "BAGS");
        row.put("subcategory", "BACKPACK");
        row.put("title", "Black canvas backpack");
        row.put("description", "Black canvas backpack with a silver zipper and yellow patch");
        row.put("location", "Leavey Library lobby");
        row.put("eventDate", "2026-11-20");
        row.put("color", "black");
        row.put("brand", "Fjallraven");
        return row;
    }

    private static Map<String, Object> changed(Map<String, Object> original, String key, Object value) {
        Map<String, Object> copy = new HashMap<>(original);
        copy.put(key, value);
        return copy;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
