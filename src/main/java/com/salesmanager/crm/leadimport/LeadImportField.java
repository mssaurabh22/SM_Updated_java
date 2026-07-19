package com.salesmanager.crm.leadimport;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The full importable-field vocabulary for bulk Lead import (see LeadImportService's javadoc
 * for the field-by-field mapping into Lead). {@code key()} is what appears as a
 * columnMapping/suggestedMapping JSON key (e.g. "companyName") - deliberately the same name as
 * the corresponding Lead/LeadCreateRequest property, not a separate vocabulary.
 *
 * Each field also carries a short, hand-picked list of common header synonyms used by
 * {@link #suggestMapping(List)} to auto-suggest a column mapping from an uploaded file's header
 * row - deliberately simple, case-insensitive/punctuation-insensitive EXACT phrase matching
 * (not fuzzy/NLP matching): a header like "Company Name" matches via the field's own humanized
 * name ("companyName" -> "company name") with no synonym needed, while "Business Name" matches
 * via an explicit synonym. The admin always sees and can edit the suggestion before commit, so
 * a missed/wrong guess here is a UX inconvenience, never a correctness problem.
 */
public enum LeadImportField {

    COMPANY_NAME("companyName", List.of(
            "company", "business name", "organisation", "organization", "organization name",
            "client name", "customer name")),
    CONTACT_PERSON("contactPerson", List.of(
            "contact", "name", "contact name", "person name")),
    CONTACT_NO("contactNo", List.of(
            "phone", "contact number", "mobile", "mobile number", "phone number", "telephone")),
    EMAIL("email", List.of("email address", "e mail", "mail")),
    INDUSTRY("industry", List.of("industry type", "sector")),
    BUSINESS_TYPE("businessType", List.of("type of business", "business category")),
    LEAD_SOURCE("leadSource", List.of("source", "source of lead", "referral source")),
    DESIGNATION("designation", List.of("title", "job title", "position")),
    STATE("state", List.of("province", "state name")),
    CITY("city", List.of("town", "location")),
    TURNOVER("turnover", List.of("annual turnover", "revenue", "company turnover")),
    REQUIREMENTS("requirements", List.of("requirement", "needs", "customer needs")),
    CURRENT_PRODUCT_SOLUTION("currentProductSolution", List.of(
            "current solution", "current product", "existing solution", "existing product")),
    BUDGET_RANGE("budgetRange", List.of("budget", "estimated budget")),
    REMARKS("remarks", List.of("notes", "comment", "comments", "remark")),
    STATUS("status", List.of("lead status"));

    private final String key;
    private final List<String> synonyms;

    LeadImportField(String key, List<String> synonyms) {
        this.key = key;
        this.synonyms = synonyms;
    }

    public String key() {
        return key;
    }

    /**
     * Builds a best-guess columnMapping from a file's header row - see the class javadoc for
     * the matching rule. A header is claimed by at most one field (first field, in declaration
     * order, to match wins); a field with no matching header is simply absent from the
     * returned map.
     */
    public static Map<String, Integer> suggestMapping(List<String> headers) {
        List<String> normalizedHeaders = new ArrayList<>();
        for (String header : headers) {
            normalizedHeaders.add(normalize(header));
        }

        Map<String, Integer> mapping = new LinkedHashMap<>();
        Set<Integer> claimedColumns = new HashSet<>();
        for (LeadImportField field : values()) {
            Set<String> matchTerms = field.normalizedMatchTerms();
            for (int i = 0; i < normalizedHeaders.size(); i++) {
                if (claimedColumns.contains(i)) {
                    continue;
                }
                if (matchTerms.contains(normalizedHeaders.get(i))) {
                    mapping.put(field.key(), i);
                    claimedColumns.add(i);
                    break;
                }
            }
        }
        return mapping;
    }

    private Set<String> normalizedMatchTerms() {
        Set<String> terms = new HashSet<>();
        terms.add(normalize(humanize(key)));
        for (String synonym : synonyms) {
            terms.add(normalize(synonym));
        }
        return terms;
    }

    /** "currentProductSolution" -> "current product solution". */
    private static String humanize(String camelCase) {
        StringBuilder result = new StringBuilder();
        for (char c : camelCase.toCharArray()) {
            if (Character.isUpperCase(c) && result.length() > 0) {
                result.append(' ');
            }
            result.append(Character.toLowerCase(c));
        }
        return result.toString();
    }

    /** Lowercase, non-alphanumeric collapsed to single spaces, trimmed. */
    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String lettersAndDigitsOnly = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        return lettersAndDigitsOnly.replaceAll("\\s+", " ");
    }
}
