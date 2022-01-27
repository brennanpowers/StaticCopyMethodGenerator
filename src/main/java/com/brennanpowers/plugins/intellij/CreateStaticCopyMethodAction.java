package com.brennanpowers.plugins.intellij;

import com.intellij.codeInsight.CodeInsightActionHandler;
import com.intellij.codeInsight.generation.actions.BaseGenerateAction;

public class CreateStaticCopyMethodAction extends BaseGenerateAction {


    public CreateStaticCopyMethodAction() {
        super(new CreateStaticCopyMethodHandler());
    }

    public CreateStaticCopyMethodAction(CodeInsightActionHandler handler) {
        super(handler);
    }
}
