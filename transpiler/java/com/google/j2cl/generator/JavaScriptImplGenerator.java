/*
 * Copyright 2015 Google Inc.
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
package com.google.j2cl.generator;

import com.google.j2cl.ast.JavaType;
import com.google.j2cl.errors.Errors;

import org.apache.velocity.app.VelocityEngine;

import java.nio.charset.Charset;
import java.nio.file.FileSystem;

/**
 * Generates JavaScript source impl files.
 */
public class JavaScriptImplGenerator extends JavaScriptGenerator {

  public JavaScriptImplGenerator(
      Errors errors,
      FileSystem outputFileSystem,
      String outputLocationPath,
      Charset charset,
      JavaType javaType,
      VelocityEngine velocityEngine) {
    super(errors, outputFileSystem, outputLocationPath, charset, javaType, velocityEngine);
  }

  @Override
  public String getTemplateFilePath() {
    return "com/google/j2cl/generator/JsTypeImpl.vm";
  }

  @Override
  public String getSuffix() {
    return ".impl.js";
  }
}
