/*
 * Copyright 2020 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.transpiler.passes;

import com.google.common.collect.ImmutableMap;
import com.google.j2cl.transpiler.ast.AbstractRewriter;
import com.google.j2cl.transpiler.ast.BreakOrContinueStatement;
import com.google.j2cl.transpiler.ast.BreakStatement;
import com.google.j2cl.transpiler.ast.CompilationUnit;
import com.google.j2cl.transpiler.ast.ContinueStatement;
import com.google.j2cl.transpiler.ast.Label;
import com.google.j2cl.transpiler.ast.LabeledStatement;
import com.google.j2cl.transpiler.ast.LoopStatement;
import com.google.j2cl.transpiler.ast.Node;
import com.google.j2cl.transpiler.ast.Statement;
import com.google.j2cl.transpiler.ast.SwitchStatement;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Assigns a label to each loop and switch that does not already have one, and makes all breaks and
 * continues explicitly target a label. After this pass is ran all breaks and continues will have an
 * explicit target.
 */
public class NormalizeLabels extends NormalizationPass {

  @Override
  public void applyTo(CompilationUnit compilationUnit) {
    ImmutableMap<Class<?>, Deque<Label>> enclosingLabelsByType =
        ImmutableMap.of(
            BreakStatement.class, new ArrayDeque<>(),
            ContinueStatement.class, new ArrayDeque<>());

    compilationUnit.accept(
        new AbstractRewriter() {
          @Override
          public boolean shouldProcessLoopStatement(LoopStatement loopStatement) {
            Label enclosingLabel = getEnclosingLabel("LOOP");
            enclosingLabelsByType.get(BreakStatement.class).push(enclosingLabel);
            enclosingLabelsByType.get(ContinueStatement.class).push(enclosingLabel);
            return true;
          }

          @Override
          public boolean shouldProcessSwitchStatement(SwitchStatement switchStatement) {
            // Note that Switch statements are never targets of continue statements.
            enclosingLabelsByType.get(BreakStatement.class).push(getEnclosingLabel("SWITCH"));
            return true;
          }

          private Label getEnclosingLabel(String labelName) {
            return getParent() instanceof LabeledStatement
                ? ((LabeledStatement) getParent()).getLabel()
                : Label.newBuilder().setName(labelName).build();
          }

          @Override
          public Statement rewriteLoopStatement(LoopStatement loopStatement) {
            enclosingLabelsByType.get(ContinueStatement.class).pop();
            return ensureLabeled(loopStatement);
          }

          @Override
          public Statement rewriteSwitchStatement(SwitchStatement switchStatement) {
            return ensureLabeled(switchStatement);
          }

          private Statement ensureLabeled(Statement statement) {
            Label breakLabel = enclosingLabelsByType.get(BreakStatement.class).pop();
            if (getParent() instanceof LabeledStatement) {
              return statement;
            }

            // Make sure statement is enclosed with the label (if not already).
            return statement.encloseWithLabel(breakLabel);
          }

          @Override
          public Node rewriteBreakOrContinueStatement(
              BreakOrContinueStatement breakOrContinueStatement) {
            if (breakOrContinueStatement.getLabelReference() != null) {
              return breakOrContinueStatement;
            }

            return breakOrContinueStatement.toBuilder()
                .setLabelReference(
                    enclosingLabelsByType
                        .get(breakOrContinueStatement.getClass())
                        .peek()
                        .createReference())
                .build();
          }
        });
  }
}
