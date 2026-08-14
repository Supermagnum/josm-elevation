package no.nvdbincline.core.kommune;

import java.util.Objects;
import java.util.Optional;

/**
 * One entry from the bundled Regjeringen kommune list.
 *
 * @param nummer kommunenummer
 * @param navn kommunenavn
 * @param fylkesnummer optional; only when present in the source spreadsheet
 * @param fylkesnavn optional; only when present in the source spreadsheet
 */
public record Kommune(
        int nummer, String navn, Integer fylkesnummer, String fylkesnavn) {

    public Kommune {
        Objects.requireNonNull(navn, "navn");
        if (nummer <= 0) {
            throw new IllegalArgumentException("kommunenummer must be positive");
        }
        if (navn.isBlank()) {
            throw new IllegalArgumentException("kommunenavn blank");
        }
    }

    public Kommune(int nummer, String navn) {
        this(nummer, navn, null, null);
    }

    public Optional<Integer> fylkesnummerOpt() {
        return Optional.ofNullable(fylkesnummer);
    }

    public Optional<String> fylkesnavnOpt() {
        return Optional.ofNullable(fylkesnavn);
    }

    /** Display label for combo boxes: {@code "Oslo (301)"}. */
    public String displayLabel() {
        return navn + " (" + nummer + ")";
    }
}
