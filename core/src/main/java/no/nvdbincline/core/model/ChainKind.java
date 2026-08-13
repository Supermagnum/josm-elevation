package no.nvdbincline.core.model;

public enum ChainKind {
    FIT("fit"),
    REMOVE("remove"),
    FIT_REMOVE("fit;remove");

    private final String tagValue;

    ChainKind(String tagValue) {
        this.tagValue = tagValue;
    }

    public String tagValue() {
        return tagValue;
    }
}
