package dev.enginehost.runtime;

/** Enginehost's main process -> the runtime: one translated line, by its ehtext/1 id. */
oneway interface ITextTranslated {
    void translated(String lineId, String text);
}
