package in.gov.sarthi.assistant.faq;

import java.util.List;

/** One retrievable FAQ entry — the "corpus" the chat assistant is grounded in. */
public record FaqDocument(String id, String question, String answer, List<String> keywords) {}
