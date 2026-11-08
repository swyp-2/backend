package com.baton.member.application;

import com.baton.member.domain.ConsentDocument;

public record ConsentDocumentView(long documentId, String kind, int version, String title, boolean required,
                                  String bodyReference) {

    static ConsentDocumentView from(ConsentDocument document) {
        return new ConsentDocumentView(document.getId(), document.getKind(), document.getVersion(),
                document.getTitle(), document.isRequired(), document.getBodyReference());
    }
}
