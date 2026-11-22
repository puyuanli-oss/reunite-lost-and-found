package edu.usc.lostfound;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Deterministic, explainable discovery matching. A match is never ownership proof. */
public final class MatchingEngine {
    private MatchingEngine() {}

    public static final double MIN_SCORE = 65.0;
    private static final Set<String> CATEGORIES = Set.of("BAGS", "ELECTRONICS", "IDS", "CLOTHING");
    private static final Set<String> SUBCATEGORIES = Set.of(
            "BACKPACK", "PURSE", "LAPTOP", "PHONE", "HEADPHONES", "USC_ID",
            "DRIVERS_LICENSE", "PASSPORT", "JACKET", "SHIRT", "WALLET", "KEYS", "BOTTLE",
            "TOTE", "TABLET", "CHARGER", "HAT", "JEWELRY");
    private static final Set<String> STOP = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "been", "but", "by", "can", "did", "do",
            "for", "from", "had", "ha", "have", "i", "if", "in", "is", "it", "its", "item", "lost",
            "found", "look", "looking", "me", "my", "near", "of", "on", "or", "our", "please",
            "report", "s", "some", "that", "the", "their", "there", "these", "they", "thi", "this",
            "to", "wa", "was", "were", "when", "where", "which", "who", "with", "would", "you", "your");
    private static final Set<String> UNKNOWN = Set.of("", "UNKNOWN", "OTHER", "UNSURE", "NOT SURE", "N/A", "NONE");
    private static final Set<String> TYPE_WORDS = Set.of(
            "bag", "backpack", "purse", "laptop", "phone", "headphone", "id", "usc", "driver",
            "license", "passport", "jacket", "shirt", "wallet", "key", "bottle", "electronic",
            "clothing", "clothe", "belonging", "personal", "identification", "tote", "tablet",
            "charger", "hat", "jewelry");

    /** Reasons contain only fixed labels and scores, never secret report fields or token values. */
    public record Decision(boolean eligible, double score, List<String> reasons) {
        public Decision {
            reasons = List.copyOf(reasons);
        }
    }

    public static Decision compare(Map<String, Object> lost, Map<String, Object> found) {
        return compare(lost, found, MIN_SCORE);
    }

    /** A higher threshold can tighten discovery; callers must not lower the privacy baseline. */
    public static Decision compare(Map<String, Object> lost, Map<String, Object> found, double threshold) {
        if (!Double.isFinite(threshold) || threshold < MIN_SCORE || threshold > 100.0) {
            throw new IllegalArgumentException("Match threshold must be between 65 and 100.");
        }
        if (lost == null || found == null) return veto("Missing report.");
        if (!"LOST".equals(code(lost, "type")) || !"FOUND".equals(code(found, "type"))) {
            return veto("A lost report and a found report are required.");
        }
        if (!"OPEN".equals(code(lost, "status")) || !"OPEN".equals(code(found, "status"))) {
            return veto("Both reports must be active.");
        }
        String lostOwner = value(lost, "ownerId");
        String foundOwner = value(found, "ownerId");
        if (!lostOwner.isEmpty() && lostOwner.equals(foundOwner)) return veto("Reports have the same owner.");

        LocalDate lostDate;
        LocalDate foundDate;
        try {
            lostDate = LocalDate.parse(value(lost, "eventDate"));
            foundDate = LocalDate.parse(value(found, "eventDate"));
        } catch (DateTimeParseException e) {
            return veto("Valid event dates are required.");
        }
        if (foundDate.isBefore(lostDate)) return veto("Found date precedes lost date.");

        String lostCategory = code(lost, "category"), foundCategory = code(found, "category");
        boolean knownCategories = CATEGORIES.contains(lostCategory) && CATEGORIES.contains(foundCategory);
        if (knownCategories && !lostCategory.equals(foundCategory)) return veto("Primary item categories conflict.");
        String lostSubcategory = code(lost, "subcategory"), foundSubcategory = code(found, "subcategory");
        boolean knownSubcategories = SUBCATEGORIES.contains(lostSubcategory) && SUBCATEGORIES.contains(foundSubcategory);
        if (knownSubcategories && !lostSubcategory.equals(foundSubcategory)) return veto("Reliable item subcategories conflict.");

        Set<String> lostWords = tokens(value(lost, "title") + " " + value(lost, "description"));
        Set<String> foundWords = tokens(value(found, "title") + " " + value(found, "description"));
        Set<String> shared = new TreeSet<>(lostWords);
        shared.retainAll(foundWords);
        if (shared.isEmpty()) return veto("Descriptions share no meaningful words.");

        List<String> reasons = new ArrayList<>();
        double score = 0;
        if (knownCategories && lostCategory.equals(foundCategory)) {
            score += 30;
            reasons.add("Same primary category (+30).");
        }
        boolean sameKnownSubtype = knownSubcategories && lostSubcategory.equals(foundSubcategory);
        if (sameKnownSubtype) {
            score += 20;
            reasons.add("Same reliable subcategory (+20).");
        }
        double textPoints = 25.0 * 2 * shared.size() / (lostWords.size() + foundWords.size());
        score += textPoints;
        reasons.add("Description overlap (+" + points(textPoints) + ").");

        Set<String> lostLocation = tokens(value(lost, "location"));
        Set<String> foundLocation = tokens(value(found, "location"));
        if (!lostLocation.isEmpty() && !foundLocation.isEmpty()) {
            Set<String> union = new LinkedHashSet<>(lostLocation);
            union.addAll(foundLocation);
            Set<String> overlap = new LinkedHashSet<>(lostLocation);
            overlap.retainAll(foundLocation);
            double locationPoints = 10.0 * overlap.size() / union.size();
            score += locationPoints;
            if (locationPoints > 0) reasons.add("Location overlap (+" + points(locationPoints) + ").");
        }
        long days = ChronoUnit.DAYS.between(lostDate, foundDate);
        double datePoints = 10.0 * Math.pow(0.5, days / 14.0);
        score += datePoints;
        reasons.add("Date proximity (+" + points(datePoints) + ").");
        if (sameKnownText(lost, found, "color")) {
            score += 3;
            reasons.add("Same reported color (+3).");
        }
        if (sameKnownText(lost, found, "brand")) {
            score += 2;
            reasons.add("Same reported brand (+2).");
        }

        score = Math.round(Math.min(100.0, Math.max(0, score)) * 100.0) / 100.0;
        boolean hasDescriptor = shared.stream().anyMatch(word -> !TYPE_WORDS.contains(word));
        boolean enoughText = hasDescriptor && (shared.size() >= 2 || sameKnownSubtype);
        if (!enoughText) reasons.add("Insufficient descriptive evidence: add detail to the lost report.");
        if (score < threshold) reasons.add("Evidence is below the discovery threshold.");
        return new Decision(enoughText && score >= threshold, score, reasons);
    }

    private static Decision veto(String reason) {
        return new Decision(false, 0, List.of(reason));
    }

    private static String value(Map<String, Object> report, String key) {
        Object v = report.get(key);
        return v == null ? "" : v.toString().trim();
    }

    private static String code(Map<String, Object> report, String key) {
        return normalize(value(report, key)).toUpperCase(Locale.ROOT);
    }

    private static boolean sameKnownText(Map<String, Object> a, Map<String, Object> b, String key) {
        String av = code(a, key), bv = code(b, key);
        return !UNKNOWN.contains(av) && !UNKNOWN.contains(bv) && av.equals(bv);
    }

    private static String points(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ");
    }

    /** Case-insensitive, Unicode-aware word sets with conservative English plural normalization. */
    public static Set<String> tokens(String text) {
        if (text == null || text.isBlank()) return Set.of();
        Set<String> result = new TreeSet<>();
        for (String raw : normalize(text).toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (raw.length() < 2 || STOP.contains(raw)) continue;
            String word = singular(raw);
            if (word.length() >= 2 && !STOP.contains(word)) result.add(word);
        }
        return Collections.unmodifiableSet(result);
    }

    private static String singular(String word) {
        if (word.equals("glasses") || word.equals("sunglasses")) return word;
        if (word.length() > 4 && word.endsWith("ies") && !word.equals("series")) return word.substring(0, word.length() - 3) + "y";
        if (word.length() > 4 && (word.endsWith("ches") || word.endsWith("shes") || word.endsWith("xes") || word.endsWith("zes") || word.endsWith("sses"))) {
            return word.substring(0, word.length() - 2);
        }
        if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") && !word.endsWith("is")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }
}
