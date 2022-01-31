package com.brennanpowers.plugins.intellij;

import com.intellij.codeInsight.CodeInsightActionHandler;
import com.intellij.codeInsight.generation.actions.BaseGenerateAction;
import com.intellij.psi.PsiAnonymousClass;
import com.intellij.psi.PsiClass;

public class CreateStaticCopyMethodAction extends BaseGenerateAction {

    public CreateStaticCopyMethodAction() {
        super(new CreateStaticCopyMethodHandler());
    }

    public CreateStaticCopyMethodAction(final CodeInsightActionHandler handler) {
        super(handler);
    }

    @Override
    protected boolean isValidForClass(PsiClass targetClass) {
        boolean hasStaticCopyMethodAlready = PsiTools.findStaticCopyMethodForClass(targetClass).isPresent();
        return !hasStaticCopyMethodAlready
                && !targetClass.isEnum()
                && !targetClass.isInterface()
                && !(targetClass instanceof PsiAnonymousClass)
                ;
    }
}
