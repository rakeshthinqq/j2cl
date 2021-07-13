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
package com.google.j2cl.transpiler.backend.wasm;

import static com.google.common.base.Predicates.not;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.lang.String.format;
import static java.util.Comparator.naturalOrder;

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import com.google.j2cl.common.OutputUtils.Output;
import com.google.j2cl.common.Problems;
import com.google.j2cl.transpiler.ast.AbstractVisitor;
import com.google.j2cl.transpiler.ast.ArrayTypeDescriptor;
import com.google.j2cl.transpiler.ast.CompilationUnit;
import com.google.j2cl.transpiler.ast.DeclaredTypeDescriptor;
import com.google.j2cl.transpiler.ast.Field;
import com.google.j2cl.transpiler.ast.Library;
import com.google.j2cl.transpiler.ast.Method;
import com.google.j2cl.transpiler.ast.MethodDescriptor;
import com.google.j2cl.transpiler.ast.Type;
import com.google.j2cl.transpiler.ast.TypeDeclaration;
import com.google.j2cl.transpiler.ast.TypeDescriptor;
import com.google.j2cl.transpiler.ast.TypeDescriptors;
import com.google.j2cl.transpiler.ast.Variable;
import com.google.j2cl.transpiler.backend.common.SourceBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Generates a WASM module containing all the code for the application. */
public class WasmModuleGenerator {

  private final Problems problems;
  private final Output output;
  private final Set<String> pendingEntryPoints;
  private final SourceBuilder builder = new SourceBuilder();
  private GenerationEnvironment environment;

  public WasmModuleGenerator(Output output, ImmutableSet<String> entryPoints, Problems problems) {
    this.output = output;
    this.pendingEntryPoints = new HashSet<>(entryPoints);
    this.problems = problems;
  }

  public void generateOutputs(Library library) {
    copyJavaSources(library);
    generateWasmModule(library);
  }

  private void copyJavaSources(Library library) {
    for (CompilationUnit compilationUnit : library.getCompilationUnits()) {
      output.copyFile(compilationUnit.getFilePath(), compilationUnit.getPackageRelativePath());
    }
  }

  private void generateWasmModule(Library library) {
    environment = new GenerationEnvironment(library);
    builder.appendln(";;; Code generated by J2WASM");
    builder.append("(module");
    // Declare an tag that will be used for Java exceptions. The tag has a single parameter that is
    // the Throwable object being thrown by the throw instruction.
    // The throw instruction will refer to this tag and will expect a single element in the stack
    // with the type $java.lang.Throwable.
    builder.newLine();
    builder.append("(tag $exception.event (param (ref null $java.lang.Throwable)))");
    emitRttHierarchy(library);
    emitGlobals(library);
    emitDynamicDispatchMethodTypes(library);
    emitTypes(library);
    emitItableSupportTypes();
    emitRuntimeInitialization(library);
    emitNativeArrayTypes(library);
    builder.newLine();
    builder.append(")");
    output.write("module.wat", builder.build());
    if (!pendingEntryPoints.isEmpty()) {
      problems.error("Static entry points %s not found.", pendingEntryPoints);
    }
  }

  private void emitItableSupportTypes() {
    builder.newLine();
    // The itable is an array of interface vtables. However since there is no common super type
    // for all vtables, the array is defined as array of 'data' which is the top type for structs.
    builder.append("(type $itable (array (mut (ref null data))))");
  }

  private void emitGlobals(Library library) {
    emitDispatchTableGlobals(library);
    emitStaticFieldGlobals(library);
  }

  /** Emit the type for all function signatures that will be needed to reference vtable methods. */
  private void emitDynamicDispatchMethodTypes(Library library) {
    Set<String> emittedFunctionTypeNames = new HashSet<>();
    library.getCompilationUnits().stream()
        .flatMap(cu -> cu.getTypes().stream())
        .flatMap(t -> t.getMethods().stream())
        .map(Method::getDescriptor)
        .filter(MethodDescriptor::isPolymorphic)
        .forEach(m -> emitFunctionType(emittedFunctionTypeNames, m));
  }

  private void emitFunctionType(Set<String> emittedFunctionTypeNames, MethodDescriptor m) {
    String typeName = environment.getFunctionTypeName(m);
    if (!emittedFunctionTypeNames.add(typeName)) {
      return;
    }
    builder.newLine();
    builder.append(String.format("(type %s (func", typeName));
    Streams.concat(
            // Add the implicit parameter
            Stream.of(TypeDescriptors.get().javaLangObject),
            m.getDispatchParameterTypeDescriptors().stream())
        .forEach(t -> builder.append(String.format(" (param %s)", environment.getWasmType(t))));

    TypeDescriptor returnTypeDescriptor = m.getDispatchReturnTypeDescriptor();
    if (!TypeDescriptors.isPrimitiveVoid(returnTypeDescriptor)) {
      builder.append(String.format(" (result %s)", environment.getWasmType(returnTypeDescriptor)));
    }
    builder.append("))");
  }

  private void emitTypes(Library library) {
    for (CompilationUnit compilationUnit : library.getCompilationUnits()) {
      for (Type type : compilationUnit.getTypes()) {
        if (type.getTypeDescriptor().isWasmExtern()) {
          continue;
        }
        emitBeginCodeComment(type, type.getKind().name());
        renderType(type);
        emitEndCodeComment(type, type.getKind().name());
      }
    }
  }

  /** Emits the rtt hierarchy by assigning a global to each rtt. */
  private void emitRttHierarchy(Library library) {
    // TODO(b/174715079): Consider tagging or emitting together with the rest of the type
    // to make the rtts show in the readables.
    Set<TypeDeclaration> emittedRtts = new HashSet<>();
    library.getCompilationUnits().stream()
        .flatMap(c -> c.getTypes().stream())
        .filter(not(Type::isInterface)) // Interfaces do not have rtts.
        .forEach(t -> emitRttGlobal(t.getDeclaration(), emittedRtts));
  }

  private void emitRttGlobal(TypeDeclaration typeDeclaration, Set<TypeDeclaration> emittedRtts) {
    if (!emittedRtts.add(typeDeclaration)) {
      return;
    }
    DeclaredTypeDescriptor superTypeDescriptor = typeDeclaration.getSuperTypeDescriptor();
    if (superTypeDescriptor != null) {
      // Supertype rtt needs to be emitted before the subtype since globals can only refer to
      // globals that are initialized before.
      emitRttGlobal(superTypeDescriptor.getTypeDeclaration(), emittedRtts);
    }
    // rtt starts at 0
    int depth = typeDeclaration.getClassHierarchyDepth() - 1;
    String wasmTypeName = environment.getWasmTypeName(typeDeclaration) + "";
    String superTypeRtt =
        superTypeDescriptor == null
            ? "(rtt.canon " + wasmTypeName + ")"
            : format(
                "(rtt.sub %s (global.get %s))",
                wasmTypeName,
                environment.getRttGlobalName(superTypeDescriptor.getTypeDeclaration()) + "");
    builder.newLine();
    builder.append(
        format(
            "(global %s (rtt %d %s) %s)",
            environment.getRttGlobalName(typeDeclaration) + "", depth, wasmTypeName, superTypeRtt));
  }

  private void renderType(Type type) {
    if (type.isInterface()) {
      // Interfaces at runtime are treated as java.lang.Object; they don't have an empty structure
      // nor rtts.
      renderInterfaceVtableStruct(type);
    } else {
      renderTypeStruct(type);
      renderVtableStruct(type);
    }

    renderTypeMethods(type);
  }

  /**
   * Renders the struct for the vtable of an interface.
   *
   * <p>There is a vtable for each interface, and it consists of fields only for the methods
   * declared in that interface (not including methods declared in their supers). Calls to interface
   * methods will always point to an interface that declared them.
   */
  private void renderInterfaceVtableStruct(Type type) {
    // TODO(b/186472671): centralize all concepts related to layout in WasmTypeLayout, including
    // interface vtables and slot assignments.
    builder.newLine();
    builder.append(
        String.format(
            "(type %s (struct", environment.getWasmVtableTypeName(type.getTypeDescriptor())));
    builder.indent();
    renderInterfaceVtableTypeFields(type.getDeclaration());
    builder.unindent();
    builder.newLine();
    builder.append("))");
  }

  private void renderInterfaceVtableTypeFields(TypeDeclaration typeDeclaration) {
    for (MethodDescriptor methodDescriptor : typeDeclaration.getDeclaredMethodDescriptors()) {
      if (!methodDescriptor.isPolymorphic()) {
        continue;
      }
      String functionTypeName = environment.getFunctionTypeName(methodDescriptor);
      builder.newLine();
      builder.append(
          String.format(
              "(field $%s (mut (ref %s)))", methodDescriptor.getMangledName(), functionTypeName));
    }
  }

  private void emitStaticFieldGlobals(Library library) {
    library.getCompilationUnits().stream()
        .flatMap(c -> c.getTypes().stream())
        .forEach(this::emitStaticFieldGlobals);
  }

  private void emitStaticFieldGlobals(Type type) {
    emitBeginCodeComment(type, "static fields");
    for (Field field : type.getStaticFields()) {
      builder.newLine();
      builder.append("(global " + environment.getFieldName(field));

      if (field.isCompileTimeConstant()) {
        builder.append(
            String.format(
                " %s ", environment.getWasmType(field.getDescriptor().getTypeDescriptor())));
        ExpressionTranspiler.render(field.getInitializer(), builder, environment);
      } else {
        builder.append(
            String.format(
                " (mut %s) ", environment.getWasmType(field.getDescriptor().getTypeDescriptor())));
        ExpressionTranspiler.render(
            field.getDescriptor().getTypeDescriptor().getDefaultValue(), builder, environment);
      }

      builder.append(")");
    }
    emitEndCodeComment(type, "static fields");
  }

  private void renderTypeMethods(Type type) {
    type.getMethods().stream()
        .filter(not(Method::isAbstract))
        .filter(m -> m.getDescriptor().getWasmInfo() == null)
        .forEach(this::renderMethod);
  }

  private void renderMethod(Method method) {
    builder.newLine();
    builder.newLine();
    builder.append(";;; " + method.getReadableDescription());
    builder.newLine();
    builder.append("(func " + environment.getMethodImplementationName(method.getDescriptor()));

    boolean isStaticExtern = method.getDescriptor().isExtern() && method.getDescriptor().isStatic();
    if (isStaticExtern) {
      builder.append(
          String.format(
              " (import \"imports\" \"%s\") ", method.getDescriptor().getQualifiedJsName()));
    }
    if (method.isStatic() && pendingEntryPoints.remove(method.getQualifiedBinaryName())) {
      builder.append(" (export \"" + method.getDescriptor().getName() + "\")");
    }
    MethodDescriptor methodDescriptor = method.getDescriptor();
    DeclaredTypeDescriptor enclosingTypeDescriptor = methodDescriptor.getEnclosingTypeDescriptor();

    // Emit parameters
    builder.indent();
    // Add the implicit "this" parameter to instance methods and constructors.
    // Note that constructors and private methods can declare the parameter type to be the
    // enclosing type because they are not overridden but normal instance methods have to
    // declare the parameter more generically as java.lang.Object, since all the overrides need
    // to have matching signatures.
    if (methodDescriptor.isClassDynamicDispatch()) {
      builder.newLine();
      builder.append(
          String.format(
              "(param $this.untyped %s)",
              environment.getWasmType(TypeDescriptors.get().javaLangObject)));
    } else if (!method.isStatic()) {
      // Private methods and constructors receive the instance with the actual type.
      builder.newLine();
      builder.append(
          String.format("(param $this %s)", environment.getWasmType(enclosingTypeDescriptor)));
    }

    for (Variable parameter : method.getParameters()) {
      builder.newLine();
      builder.append(
          "(param "
              + environment.getDeclarationName(parameter)
              + " "
              + environment.getWasmType(parameter.getTypeDescriptor())
              + ")");
    }

    TypeDescriptor returnTypeDescriptor = methodDescriptor.getDispatchReturnTypeDescriptor();

    // Emit return type.
    if (!TypeDescriptors.isPrimitiveVoid(returnTypeDescriptor)) {
      builder.newLine();
      builder.append("(result " + environment.getWasmType(returnTypeDescriptor) + ")");
    }

    if (isStaticExtern) {
      // Imports don't define locals nor body.
      builder.unindent();
      builder.newLine();
      builder.append(")");
      return;
    }

    // Emit locals.
    for (Variable variable : collectLocals(method)) {
      builder.newLine();
      builder.append(
          "(local "
              + environment.getDeclarationName(variable)
              + " "
              + environment.getWasmType(variable.getTypeDescriptor())
              + ")");
    }
    // Introduce the actual $this variable for polymorphic methods and cast the parameter to
    // the right type.
    if (methodDescriptor.isClassDynamicDispatch()) {
      builder.newLine();
      builder.append(
          String.format("(local $this %s)", environment.getWasmType(enclosingTypeDescriptor)));
      builder.newLine();
      builder.append(
          String.format(
              "(local.set $this (ref.cast (local.get $this.untyped) (global.get %s)))",
              environment.getRttGlobalName(enclosingTypeDescriptor)));
    }

    StatementTranspiler.render(method.getBody(), builder, environment);
    if (!TypeDescriptors.isPrimitiveVoid(returnTypeDescriptor) && method.isNative()) {
      // Unforunately we still have native method calls in JRE so we need to synthesize stubs for
      // such methods to pass WASM checks.
      builder.newLine();
      builder.append("(unreachable)");
    }
    builder.unindent();
    builder.newLine();
    builder.append(")");

    // Declare a function that will be target of dynamic dispatch.
    if (methodDescriptor.isPolymorphic()) {
      builder.newLine();
      builder.append(
          String.format(
              "(elem declare func %s)",
              environment.getMethodImplementationName(method.getDescriptor())));
    }
  }

  private static List<Variable> collectLocals(Method method) {
    List<Variable> locals = new ArrayList<>();
    method
        .getBody()
        .accept(
            new AbstractVisitor() {
              @Override
              public void exitVariable(Variable variable) {
                locals.add(variable);
              }
            });
    return locals;
  }

  private void renderTypeStruct(Type type) {
    builder.newLine();
    builder.append("(type " + environment.getWasmTypeName(type.getTypeDescriptor()) + " (struct");
    builder.indent();
    renderTypeFields(type);
    builder.unindent();
    builder.newLine();
    builder.append("))");
  }

  private void renderTypeFields(Type type) {
    builder.newLine();
    // The first field is always the vtable for class dynamic dispatch.
    builder.append(
        String.format(
            "(field $vtable (ref %s)) ",
            environment.getWasmVtableTypeName(type.getTypeDescriptor())));
    // The second field is always the itable for interface method dispatch.
    builder.append("(field $itable (ref $itable))");

    WasmTypeLayout wasmType = environment.getWasmTypeLayout(type.getDeclaration());
    for (Field field : wasmType.getAllInstanceFields()) {
      builder.newLine();
      builder.append(
          "(field "
              + environment.getFieldName(field)
              + " (mut "
              + environment.getWasmType(field.getDescriptor().getTypeDescriptor())
              + "))");
    }
  }

  private void renderVtableStruct(Type type) {
    builder.newLine();
    builder.append(
        String.format(
            "(type %s (struct", environment.getWasmVtableTypeName(type.getTypeDescriptor())));
    builder.indent();
    renderVtableTypeFields(type);
    builder.unindent();
    builder.newLine();
    builder.append("))");
  }

  private void renderVtableTypeFields(Type type) {
    WasmTypeLayout wasmTypeLayout = environment.getWasmTypeLayout(type.getDeclaration());
    builder.newLine();
    for (Method method : wasmTypeLayout.getAllPolymorphicMethods()) {
      String functionTypeName = environment.getFunctionTypeName(method.getDescriptor());
      builder.newLine();
      builder.append(
          String.format(
              "(field $%s (mut (ref %s)))",
              method.getDescriptor().getMangledName(), functionTypeName));
    }
  }

  private void emitDispatchTableGlobals(Library library) {
    library.getCompilationUnits().stream()
        .flatMap(c -> c.getTypes().stream())
        .filter(not(Type::isInterface)) // Interfaces at runtime are treated as java.lang.Object;
        .forEach(
            t -> {
              emitVtableGlobal(t);
              emitItableGlobal(t);
            });
  }

  private void emitVtableGlobal(Type type) {
    emitBeginCodeComment(type, "vtable");
    DeclaredTypeDescriptor typeDescriptor = type.getTypeDescriptor();
    builder.newLine();
    builder.append(
        String.format(
            "(global %s (mut (ref null %s)) (ref.null %s))",
            environment.getWasmVtableGlobalName(typeDescriptor),
            environment.getWasmVtableTypeName(typeDescriptor),
            environment.getWasmVtableTypeName(typeDescriptor)));
    emitEndCodeComment(type, "vtable");
  }

  private void emitItableGlobal(Type type) {
    DeclaredTypeDescriptor typeDescriptor = type.getTypeDescriptor();
    builder.newLine();
    builder.append(
        String.format(
            "(global %s (mut (ref null $itable)) (ref.null $itable))",
            environment.getWasmItableGlobalName(typeDescriptor)));
  }

  /**
   * Emit a function that will be used to initialize the runtime at module instantiation time;
   * together with the required type definitions.
   */
  private void emitRuntimeInitialization(Library library) {
    emitBeginCodeComment("runtime initialization");
    builder.newLine();
    builder.append("(func $.runtime.init (block ");
    builder.indent();
    builder.newLine();
    // TODO(b/183994530): Initialize dynamic dispatch tables lazily.
    builder.append(";;; Initialize dynamic dispatch tables.");
    // Populate all vtables.
    library.getCompilationUnits().stream()
        .flatMap(cu -> cu.getTypes().stream())
        .filter(Predicates.not(Type::isInterface))
        .map(Type::getDeclaration)
        .filter(Predicates.not(TypeDeclaration::isAbstract))
        .forEach(this::emitDispatchTablesInitialization);
    builder.unindent();
    builder.newLine();
    builder.append("))");
    builder.newLine();
    // Run the runtime initialization function at module instantiation.
    builder.append("(start $.runtime.init)");
    emitEndCodeComment("runtime initialization");
  }

  private void emitDispatchTablesInitialization(TypeDeclaration typeDeclaration) {
    emitClassVtableInitialization(typeDeclaration);
    emitItableInitialization(typeDeclaration);
  }

  /** Emits the code to initialize the class vtable structure for {@code typeDeclaration}. */
  private void emitClassVtableInitialization(TypeDeclaration typeDeclaration) {
    WasmTypeLayout wasmTypeLayout = environment.getWasmTypeLayout(typeDeclaration);

    emitBeginCodeComment(typeDeclaration, "vtable.init");
    builder.newLine();
    //  Create the class vtable for this type (which is either a class or an enum) and store it
    // in a global variable to be able to use it to initialize instance of this class.
    builder.append(
        String.format("(global.set %s", environment.getWasmVtableGlobalName(typeDeclaration)));
    builder.indent();
    emitVtableInitialization(
        typeDeclaration,
        wasmTypeLayout.getAllPolymorphicMethods().stream()
            .map(Method::getDescriptor)
            .collect(toImmutableList()));
    builder.unindent();
    builder.newLine();
    builder.append(")");
    emitEndCodeComment(typeDeclaration, "vtable.init");
  }

  /** Emits the code to initialize the Itable array for {@code typeDeclaration}. */
  private void emitItableInitialization(TypeDeclaration typeDeclaration) {
    emitBeginCodeComment(typeDeclaration, "itable.init");

    List<TypeDeclaration> superInterfaces =
        typeDeclaration.getAllSuperTypesIncludingSelf().stream()
            .filter(TypeDeclaration::isInterface)
            .collect(toImmutableList());

    // Determine how many slots are necessary for the itable of this class.
    int numberOfSlots =
        superInterfaces.stream().map(environment::getInterfaceSlot).max(naturalOrder()).orElse(-1)
            + 1;

    // Create the array of interface vtables of the required size and store it in a global variable
    // to be able to use it when objects of this class are instantiated.
    builder.newLine();
    builder.append(
        String.format(
            "(global.set %s (array.new_default_with_rtt $itable (i32.const %d) (rtt.canon"
                + " $itable)))",
            environment.getWasmItableGlobalName(typeDeclaration), numberOfSlots));

    superInterfaces.forEach(
        superInterface -> emitInterfaceVtableSlotAssignment(superInterface, typeDeclaration));
    emitEndCodeComment(typeDeclaration, "itable.init");
  }

  /**
   * Creates and initializes the vtable for {@code implementedType} with the methods in {@code
   * methodDescriptors}.
   *
   * <p>This is used to initialize both class vtables and interface vtables. Each concrete class
   * will have a class vtable to implement the dynamic class method dispatch and one vtable for each
   * interface it implements to implement interface dispatch.
   */
  private void emitVtableInitialization(
      TypeDeclaration implementedType, List<MethodDescriptor> methodDescriptors) {
    builder.newLine();
    // Create an instance of the vtable for the type initializing it with the methods that are
    // passed in methodDescriptors.
    builder.append(
        String.format(
            "(struct.new_with_rtt %s", environment.getWasmVtableTypeName(implementedType)));

    builder.indent();
    methodDescriptors.forEach(
        m -> {
          builder.newLine();
          builder.append(
              String.format("(ref.func %s)", environment.getMethodImplementationName(m)));
        });
    builder.newLine();
    // Always use a canonical rtt for vtables.
    // Vtables corresponding the the class dynamic dispatch never require casting; their assignments
    // are always statically sound. On the other hand interface dispatch vtables require a cast
    // but they are only cast to their specific type.
    builder.append(
        String.format("(rtt.canon %s)", environment.getWasmVtableTypeName(implementedType)));
    builder.unindent();
    builder.newLine();
    builder.append(")");
  }

  /**
   * Assigns the vtable for {@code superInterface} to the corresponding slot in {@code
   * typeDeclaration} itable.
   */
  private void emitInterfaceVtableSlotAssignment(
      TypeDeclaration superInterface, TypeDeclaration typeDeclaration) {
    WasmTypeLayout wasmTypeLayout = environment.getWasmTypeLayout(typeDeclaration);

    builder.newLine();
    // Assign the interface vtable for superInterface in this concrete class to the
    // corresponding slot in the $itable field.
    builder.append(
        String.format(
            "(array.set $itable (global.get %s) (i32.const %d)",
            environment.getWasmItableGlobalName(typeDeclaration),
            environment.getInterfaceSlot(superInterface)));
    List<MethodDescriptor> interfaceMethodImplementations =
        superInterface.getDeclaredMethodDescriptors().stream()
            .filter(MethodDescriptor::isPolymorphic)
            .map(
                m ->
                    wasmTypeLayout
                        .getAllPolymorphicMethodsByMangledName()
                        .get(m.getMangledName())
                        .getDescriptor())
            .collect(toImmutableList());
    emitVtableInitialization(superInterface, interfaceMethodImplementations);
    builder.append(")");
  }

  private void emitNativeArrayTypes(Library library) {
    emitBeginCodeComment("Native Array types");

    library.accept(
        new AbstractVisitor() {
          @Override
          public void exitField(Field field) {
            TypeDescriptor typeDescriptor = field.getDescriptor().getTypeDescriptor();
            if (!typeDescriptor.isArray()) {
              return;
            }

            ArrayTypeDescriptor arrayTypeDescriptor = (ArrayTypeDescriptor) typeDescriptor;
            if (arrayTypeDescriptor.isNativeWasmArray()) {
              emitNativeArrayType(arrayTypeDescriptor);
            }
          }
        });

    emitEndCodeComment("Native Array types");
  }

  private void emitNativeArrayType(ArrayTypeDescriptor arrayTypeDescriptor) {
    builder.newLine();
    builder.append(
        format(
            "(type %s (array (mut %s)))",
            environment.getWasmTypeName(arrayTypeDescriptor),
            environment.getWasmType(arrayTypeDescriptor.getComponentTypeDescriptor())));
    builder.newLine();
  }

  private void emitBeginCodeComment(Type type, String section) {
    emitBeginCodeComment(type.getDeclaration(), section);
  }

  private void emitBeginCodeComment(TypeDeclaration typeDeclaration, String section) {
    emitBeginCodeComment(
        String.format("%s [%s]", typeDeclaration.getQualifiedSourceName(), section));
  }

  private void emitBeginCodeComment(String commentId) {
    builder.newLine();
    builder.append(";;; Code for " + commentId);
  }

  private void emitEndCodeComment(Type type, String section) {
    emitEndCodeComment(type.getDeclaration(), section);
  }

  private void emitEndCodeComment(TypeDeclaration typeDeclaration, String section) {
    emitEndCodeComment(String.format("%s [%s]", typeDeclaration.getQualifiedSourceName(), section));
  }

  private void emitEndCodeComment(String commentId) {
    builder.newLine();
    builder.append(";;; End of code for " + commentId);
  }
}
