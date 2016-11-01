/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.turbine.parse;

import static com.google.turbine.parse.Token.COMMA;
import static com.google.turbine.parse.Token.INTERFACE;
import static com.google.turbine.parse.Token.LPAREN;
import static com.google.turbine.parse.Token.RPAREN;
import static com.google.turbine.tree.TurbineModifier.PROTECTED;
import static com.google.turbine.tree.TurbineModifier.PUBLIC;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.model.TurbineConstantTypeKind;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.Tree.Anno;
import com.google.turbine.tree.Tree.ArrTy;
import com.google.turbine.tree.Tree.ClassTy;
import com.google.turbine.tree.Tree.CompUnit;
import com.google.turbine.tree.Tree.Expression;
import com.google.turbine.tree.Tree.ImportDecl;
import com.google.turbine.tree.Tree.MethDecl;
import com.google.turbine.tree.Tree.PkgDecl;
import com.google.turbine.tree.Tree.PrimTy;
import com.google.turbine.tree.Tree.TyDecl;
import com.google.turbine.tree.Tree.TyParam;
import com.google.turbine.tree.Tree.Type;
import com.google.turbine.tree.Tree.VarDecl;
import com.google.turbine.tree.Tree.WildTy;
import com.google.turbine.tree.TurbineModifier;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;

/**
 * A parser for the subset of Java required for header compilation.
 *
 * <p>See JLS 19: https://docs.oracle.com/javase/specs/jls/se8/html/jls-19.html
 */
public class Parser {

  private static final String CTOR_NAME = "<init>";
  private final Lexer lexer;

  private Token token;
  private int position;

  public static CompUnit parse(String source) {
    return parse(new SourceFile(null, source));
  }

  public static CompUnit parse(SourceFile source) {
    return new Parser(new StreamLexer(new UnicodeEscapePreprocessor(source))).compilationUnit();
  }

  private Parser(Lexer lexer) {
    this.lexer = lexer;
    this.token = lexer.next();
  }

  public CompUnit compilationUnit() {
    // TODO(cushon): consider enforcing package, import, and declaration order
    // and make it bug-compatible with javac:
    // http://mail.openjdk.java.net/pipermail/compiler-dev/2013-August/006968.html
    Optional<PkgDecl> pkg = Optional.absent();
    EnumSet<TurbineModifier> access = EnumSet.noneOf(TurbineModifier.class);
    ImmutableList.Builder<ImportDecl> imports = ImmutableList.builder();
    ImmutableList.Builder<TyDecl> decls = ImmutableList.builder();
    ImmutableList.Builder<Anno> annos = ImmutableList.builder();
    while (true) {
      switch (token) {
        case PACKAGE:
          {
            next();
            pkg = Optional.of(packageDeclaration(annos.build()));
            annos = ImmutableList.builder();
            break;
          }
        case IMPORT:
          {
            next();
            ImportDecl i = importDeclaration();
            if (i == null) {
              continue;
            }
            imports.add(i);
            break;
          }
        case PUBLIC:
          next();
          access.add(PUBLIC);
          break;
        case PROTECTED:
          next();
          access.add(PROTECTED);
          break;
        case PRIVATE:
          next();
          access.add(TurbineModifier.PRIVATE);
          break;
        case STATIC:
          next();
          access.add(TurbineModifier.STATIC);
          break;
        case ABSTRACT:
          next();
          access.add(TurbineModifier.ABSTRACT);
          break;
        case FINAL:
          next();
          access.add(TurbineModifier.FINAL);
          break;
        case STRICTFP:
          next();
          access.add(TurbineModifier.STRICTFP);
          break;
        case AT:
          {
            next();
            if (token == INTERFACE) {
              decls.add(annotationDeclaration(access, annos.build()));
              access = EnumSet.noneOf(TurbineModifier.class);
              annos = ImmutableList.builder();
            } else {
              annos.add(annotation());
            }
            break;
          }
        case CLASS:
          decls.add(classDeclaration(access, annos.build()));
          access = EnumSet.noneOf(TurbineModifier.class);
          annos = ImmutableList.builder();
          break;
        case INTERFACE:
          decls.add(interfaceDeclaration(access, annos.build()));
          access = EnumSet.noneOf(TurbineModifier.class);
          annos = ImmutableList.builder();
          break;
        case ENUM:
          decls.add(enumDeclaration(access, annos.build()));
          access = EnumSet.noneOf(TurbineModifier.class);
          annos = ImmutableList.builder();
          break;
        case EOF:
          // TODO(cushon): check for dangling modifiers?
          return new CompUnit(position, pkg, imports.build(), decls.build(), lexer.source());
        case SEMI:
          // TODO(cushon): check for dangling modifiers?
          next();
          continue;
        default:
          throw error(token);
      }
    }
  }

  private void next() {
    token = lexer.next();
    position = lexer.position();
  }

  private TyDecl interfaceDeclaration(EnumSet<TurbineModifier> access, ImmutableList<Anno> annos) {
    eat(Token.INTERFACE);
    String name = eatIdent();
    ImmutableList<TyParam> typarams;
    if (token == Token.LT) {
      typarams = typarams();
    } else {
      typarams = ImmutableList.of();
    }
    ImmutableList.Builder<ClassTy> interfaces = ImmutableList.builder();
    if (token == Token.EXTENDS) {
      next();
      do {
        interfaces.add(classty());
      } while (maybe(Token.COMMA));
    }
    eat(Token.LBRACE);
    ImmutableList<Tree> members = classMembers();
    eat(Token.RBRACE);
    return new TyDecl(
        position,
        access,
        annos,
        name,
        typarams,
        Optional.<ClassTy>absent(),
        interfaces.build(),
        members,
        TurbineTyKind.INTERFACE);
  }

  private TyDecl annotationDeclaration(EnumSet<TurbineModifier> access, ImmutableList<Anno> annos) {
    eat(Token.INTERFACE);
    String name = eatIdent();
    eat(Token.LBRACE);
    ImmutableList<Tree> members = classMembers();
    eat(Token.RBRACE);
    return new TyDecl(
        position,
        access,
        annos,
        name,
        ImmutableList.<TyParam>of(),
        Optional.<ClassTy>absent(),
        ImmutableList.<ClassTy>of(),
        members,
        TurbineTyKind.ANNOTATION);
  }

  private TyDecl enumDeclaration(EnumSet<TurbineModifier> access, ImmutableList<Anno> annos) {
    eat(Token.ENUM);
    String name = eatIdent();
    ImmutableList.Builder<ClassTy> interfaces = ImmutableList.builder();
    if (token == Token.IMPLEMENTS) {
      next();
      do {
        interfaces.add(classty());
      } while (maybe(Token.COMMA));
    }
    eat(Token.LBRACE);
    ImmutableList<Tree> members =
        ImmutableList.<Tree>builder().addAll(enumMembers(name)).addAll(classMembers()).build();
    eat(Token.RBRACE);
    return new TyDecl(
        position,
        access,
        annos,
        name,
        ImmutableList.<TyParam>of(),
        Optional.<ClassTy>absent(),
        interfaces.build(),
        members,
        TurbineTyKind.ENUM);
  }

  private static final ImmutableSet<TurbineModifier> ENUM_CONSTANT_MODIFIERS =
      ImmutableSet.of(
          TurbineModifier.PUBLIC,
          TurbineModifier.STATIC,
          TurbineModifier.ACC_ENUM,
          TurbineModifier.FINAL);

  private ImmutableList<Tree> enumMembers(String enumName) {
    ImmutableList.Builder<Tree> result = ImmutableList.builder();
    ImmutableList.Builder<Anno> annos = ImmutableList.builder();
    OUTER:
    while (true) {
      switch (token) {
        case IDENT:
          {
            String name = eatIdent();
            if (token == Token.LPAREN) {
              dropParens();
            }
            // TODO(cushon): consider desugaring enum constants later
            EnumSet<TurbineModifier> access = EnumSet.copyOf(ENUM_CONSTANT_MODIFIERS);
            if (token == Token.LBRACE) {
              access.add(TurbineModifier.ENUM_IMPL);
              dropBlocks();
            }
            maybe(Token.COMMA);
            result.add(
                new VarDecl(
                    position,
                    access,
                    annos.build(),
                    new ClassTy(
                        position,
                        Optional.<ClassTy>absent(),
                        enumName,
                        ImmutableList.<Type>of(),
                        ImmutableList.of()),
                    name,
                    Optional.<Expression>absent()));
            annos = ImmutableList.builder();
            break;
          }
        case SEMI:
          next();
          annos = ImmutableList.builder();
          break OUTER;
        case RBRACE:
          annos = ImmutableList.builder();
          break OUTER;
        case AT:
          next();
          annos.add(annotation());
          break;
        default:
          throw error(token);
      }
    }
    return result.build();
  }

  private TyDecl classDeclaration(EnumSet<TurbineModifier> access, ImmutableList<Anno> annos) {
    eat(Token.CLASS);
    String name = eatIdent();
    ImmutableList<TyParam> tyParams = ImmutableList.of();
    if (token == Token.LT) {
      tyParams = typarams();
    }
    ClassTy xtnds = null;
    if (token == Token.EXTENDS) {
      next();
      xtnds = classty();
    }
    ImmutableList.Builder<ClassTy> interfaces = ImmutableList.builder();
    if (token == Token.IMPLEMENTS) {
      next();
      do {
        interfaces.add(classty());
      } while (maybe(Token.COMMA));
    }
    eat(Token.LBRACE);
    ImmutableList<Tree> members = classMembers();
    eat(Token.RBRACE);
    return new TyDecl(
        position,
        access,
        annos,
        name,
        tyParams,
        Optional.fromNullable(xtnds),
        interfaces.build(),
        members,
        TurbineTyKind.CLASS);
  }

  private ImmutableList<Tree> classMembers() {
    ImmutableList.Builder<Tree> acc = ImmutableList.builder();
    EnumSet<TurbineModifier> access = EnumSet.noneOf(TurbineModifier.class);
    ImmutableList.Builder<Anno> annos = ImmutableList.builder();
    while (true) {
      switch (token) {
        case PUBLIC:
          next();
          access.add(TurbineModifier.PUBLIC);
          break;
        case PROTECTED:
          next();
          access.add(TurbineModifier.PROTECTED);
          break;
        case PRIVATE:
          next();
          access.add(TurbineModifier.PRIVATE);
          break;
        case STATIC:
          next();
          access.add(TurbineModifier.STATIC);
          break;
        case ABSTRACT:
          next();
          access.add(TurbineModifier.ABSTRACT);
          break;
        case FINAL:
          next();
          access.add(TurbineModifier.FINAL);
          break;
        case NATIVE:
          next();
          access.add(TurbineModifier.NATIVE);
          break;
        case SYNCHRONIZED:
          next();
          access.add(TurbineModifier.SYNCHRONIZED);
          break;
        case TRANSIENT:
          next();
          access.add(TurbineModifier.TRANSIENT);
          break;
        case VOLATILE:
          next();
          access.add(TurbineModifier.VOLATILE);
          break;
        case STRICTFP:
          next();
          access.add(TurbineModifier.STRICTFP);
          break;
        case DEFAULT:
          next();
          access.add(TurbineModifier.DEFAULT);
          break;
        case AT:
          {
            // TODO(cushon): de-dup with top-level parsing
            next();
            if (token == INTERFACE) {
              acc.add(annotationDeclaration(access, annos.build()));
              access = EnumSet.noneOf(TurbineModifier.class);
              annos = ImmutableList.builder();
            } else {
              annos.add(annotation());
            }
            break;
          }

        case IDENT:
        case BOOLEAN:
        case BYTE:
        case SHORT:
        case INT:
        case LONG:
        case CHAR:
        case DOUBLE:
        case FLOAT:
        case VOID:
        case LT:
          acc.addAll(classMember(access, annos.build()));
          access = EnumSet.noneOf(TurbineModifier.class);
          annos = ImmutableList.builder();
          break;
        case LBRACE:
          dropBlocks();
          access = EnumSet.noneOf(TurbineModifier.class);
          annos = ImmutableList.builder();
          break;
        case CLASS:
          acc.add(classDeclaration(access, annos.build()));
          access = EnumSet.noneOf(TurbineModifier.class);
          annos = ImmutableList.builder();
          break;
        case INTERFACE:
          acc.add(interfaceDeclaration(access, annos.build()));
          access = EnumSet.noneOf(TurbineModifier.class);
          annos = ImmutableList.builder();
          break;
        case ENUM:
          acc.add(enumDeclaration(access, annos.build()));
          access = EnumSet.noneOf(TurbineModifier.class);
          annos = ImmutableList.builder();
          break;
        case RBRACE:
          return acc.build();
        case SEMI:
          next();
          continue;
        default:
          throw error(token);
      }
    }
  }

  private ImmutableList<Tree> classMember(
      EnumSet<TurbineModifier> access, ImmutableList<Anno> annos) {
    ImmutableList<TyParam> typaram = ImmutableList.of();
    Type result;
    String name;

    if (token == Token.LT) {
      typaram = typarams();
    }

    ImmutableList<Anno> typeAnnos = maybeAnnos();

    switch (token) {
      case VOID:
        {
          result = new Tree.VoidTy(position, typeAnnos);
          next();
          name = eatIdent();
          return memberRest(access, annos, typaram, result, name);
        }
      case BOOLEAN:
      case BYTE:
      case SHORT:
      case INT:
      case LONG:
      case CHAR:
      case DOUBLE:
      case FLOAT:
        {
          result = referenceType(typeAnnos);
          name = eatIdent();
          return memberRest(access, annos, typaram, result, name);
        }
      case IDENT:
        {
          String ident = eatIdent();
          switch (token) {
            case LPAREN:
              {
                name = ident;
                return ImmutableList.of(methodRest(access, annos, typaram, null, name));
              }
            case IDENT:
              {
                result =
                    new ClassTy(
                        position,
                        Optional.<ClassTy>absent(),
                        ident,
                        ImmutableList.<Type>of(),
                        typeAnnos);
                name = eatIdent();
                return memberRest(access, annos, typaram, result, name);
              }
            case AT:
            case LBRACK:
              {
                result =
                    new ClassTy(
                        position,
                        Optional.<ClassTy>absent(),
                        ident,
                        ImmutableList.<Type>of(),
                        typeAnnos);
                typeAnnos = maybeAnnos();
                eat(Token.LBRACK);
                do {
                  result = new ArrTy(position, typeAnnos, result);
                  eat(Token.RBRACK);
                  typeAnnos = maybeAnnos();
                } while (maybe(Token.LBRACK));
                break;
              }
            case LT:
              {
                result =
                    new ClassTy(position, Optional.<ClassTy>absent(), ident, tyargs(), typeAnnos);
                while (maybe(Token.LBRACK)) {
                  eat(Token.RBRACK);
                  result = new ArrTy(position, typeAnnos, result);
                }
                break;
              }
            case DOT:
              result =
                  new ClassTy(
                      position,
                      Optional.<ClassTy>absent(),
                      ident,
                      ImmutableList.<Type>of(),
                      typeAnnos);
              break;
            default:
              throw error(token);
          }
          if (result == null) {
            throw error(token);
          }
          if (token == Token.DOT) {
            next();
            // TODO(cushon): is this cast OK?
            result = classty((ClassTy) result);
            while (maybe(Token.LBRACK)) {
              eat(Token.RBRACK);
              result = new ArrTy(position, typeAnnos, result);
            }
          }
          name = eatIdent();
          switch (token) {
            case LPAREN:
              return ImmutableList.of(methodRest(access, annos, typaram, result, name));
            case LBRACK:
            case SEMI:
            case ASSIGN:
            case COMMA:
              {
                if (!typaram.isEmpty()) {
                  throw error("%s", typaram);
                }
                return fieldRest(access, annos, result, name);
              }
            default:
              throw error(token);
          }
        }
      default:
        throw error(token);
    }
  }

  private ImmutableList<Anno> maybeAnnos() {
    ImmutableList<Anno> typeAnnos = ImmutableList.of();
    if (token == Token.AT) {
      ImmutableList.Builder<Anno> builder = ImmutableList.builder();
      while (token == Token.AT) {
        next();
        builder.add(annotation());
      }
      typeAnnos = builder.build();
    }
    return typeAnnos;
  }

  private ImmutableList<Tree> memberRest(
      EnumSet<TurbineModifier> access,
      ImmutableList<Anno> annos,
      ImmutableList<TyParam> typaram,
      Type result,
      String name) {
    switch (token) {
      case ASSIGN:
      case SEMI:
      case LBRACK:
      case COMMA:
        {
          if (!typaram.isEmpty()) {
            throw error("%s", typaram);
          }
          return fieldRest(access, annos, result, name);
        }
      case LPAREN:
        return ImmutableList.of(methodRest(access, annos, typaram, result, name));
      default:
        throw error(token);
    }
  }

  private ImmutableList<Tree> fieldRest(
      EnumSet<TurbineModifier> access, ImmutableList<Anno> annos, Type baseTy, String name) {
    ImmutableList.Builder<Tree> result = ImmutableList.builder();
    VariableInitializerParser initializerParser = new VariableInitializerParser(token, lexer);
    List<List<SavedToken>> bits = initializerParser.parseInitializers();
    token = initializerParser.token;

    boolean first = true;
    for (List<SavedToken> bit : bits) {

      Iterator<SavedToken> it = bit.iterator();

      if (first) {
        first = false;
      } else {
        SavedToken next = it.next();
        if (next.token == Token.IDENT) {
          name = next.value;
        } else {
          throw error("%s", next);
        }
      }

      Type ty = baseTy;
      if (it.hasNext()) {
        SavedToken next = it.next();
        while (next.token == Token.LBRACK) {
          // TODO(cushon): type annotations on c-style array dims
          ty = new ArrTy(position, ImmutableList.of(), ty);
          next = it.next();
          if (next.token != Token.RBRACK) {
            throw error("%s", next);
          }
          if (it.hasNext()) {
            next = it.next();
          }
        }
      }
      // TODO(cushon): skip more fields that are definitely non-const
      IteratorLexer lexer = new IteratorLexer(this.lexer.source(), it);
      Expression init = new ConstExpressionParser(lexer, lexer.next()).expression();
      if (init != null && init.kind() == Tree.Kind.ARRAY_INIT) {
        init = null;
      }
      result.add(new VarDecl(position, access, annos, ty, name, Optional.fromNullable(init)));
    }
    eat(Token.SEMI);
    return result.build();
  }

  private Tree methodRest(
      EnumSet<TurbineModifier> access,
      ImmutableList<Anno> annos,
      ImmutableList<TyParam> typaram,
      Type result,
      String name) {
    eat(Token.LPAREN);
    ImmutableList.Builder<VarDecl> formals = ImmutableList.builder();
    formalParams(formals, access);
    eat(Token.RPAREN);

    if (token == Token.LBRACK) {
      // TODO(cushon): support type annotations here. or not.
      while (maybe(Token.LBRACK)) {
        eat(Token.RBRACK);
        result = new ArrTy(position, ImmutableList.of(), result);
      }
    }

    ImmutableList.Builder<ClassTy> exceptions = ImmutableList.builder();
    if (token == Token.THROWS) {
      next();
      exceptions.addAll(exceptions());
    }
    Tree defaultValue = null;
    switch (token) {
      case SEMI:
        next();
        break;
      case LBRACE:
        dropBlocks();
        break;
      case DEFAULT:
        {
          ConstExpressionParser cparser = new ConstExpressionParser(lexer, lexer.next());
          Tree expr = cparser.expression();
          token = cparser.token;
          if (expr == null && token == Token.AT) {
            next();
            expr = annotation();
          }
          if (expr == null) {
            throw error(token);
          }
          defaultValue = expr;
          eat(Token.SEMI);
          break;
        }
      default:
        throw error(token);
    }
    if (result == null) {
      name = CTOR_NAME;
    }
    return new MethDecl(
        position,
        access,
        annos,
        typaram,
        Optional.<Tree>fromNullable(result),
        name,
        formals.build(),
        exceptions.build(),
        Optional.fromNullable(defaultValue));
  }

  private ImmutableList<ClassTy> exceptions() {
    ImmutableList.Builder<ClassTy> result = ImmutableList.builder();
    result.add(classty());
    while (maybe(Token.COMMA)) {
      result.add(classty());
    }
    return result.build();
  }

  private void formalParams(
      ImmutableList.Builder<VarDecl> builder, EnumSet<TurbineModifier> access) {
    while (token != Token.RPAREN) {
      VarDecl formal = formalParam();
      builder.add(formal);
      if (formal.mods().contains(TurbineModifier.VARARGS)) {
        access.add(TurbineModifier.VARARGS);
      }
      if (token != Token.COMMA) {
        break;
      }
      next();
    }
  }

  private VarDecl formalParam() {
    ImmutableList.Builder<Anno> annos = ImmutableList.builder();
    EnumSet<TurbineModifier> access = modifiers(annos);
    Type ty = referenceType(maybeAnnos());
    ImmutableList<Anno> typeAnnos = maybeAnnos();
    if (maybe(Token.ELLIPSIS)) {
      access.add(TurbineModifier.VARARGS);
      ty = new ArrTy(position, typeAnnos, ty);
    }
    while (token == Token.LBRACK) {
      eat(Token.LBRACK);
      eat(Token.RBRACK);
      ty = new ArrTy(position, typeAnnos, ty);
      typeAnnos = maybeAnnos();
    }
    // the parameter name is `this` for receiver parameters, and a qualified this expression
    // for inner classes
    String name = identOrThis();
    while (token == Token.DOT) {
      eat(Token.DOT);
      // Overwrite everything up to the terminal 'this' for inner classes; we don't need it
      name = identOrThis();
    }
    return new VarDecl(position, access, annos.build(), ty, name, Optional.<Expression>absent());
  }

  private String identOrThis() {
    switch (token) {
      case IDENT:
        return eatIdent();
      case THIS:
        eat(Token.THIS);
        return "this";
      default:
        throw error(token);
    }
  }

  private void dropParens() {
    eat(Token.LPAREN);
    int depth = 1;
    while (depth > 0) {
      switch (token) {
        case RPAREN:
          depth--;
          break;
        case LPAREN:
          depth++;
          break;
        default:
          break;
      }
      next();
    }
  }

  private void dropBlocks() {
    eat(Token.LBRACE);
    int depth = 1;
    while (depth > 0) {
      switch (token) {
        case RBRACE:
          depth--;
          break;
        case LBRACE:
          depth++;
          break;
        default:
          break;
      }
      next();
    }
  }

  private ImmutableList<TyParam> typarams() {
    ImmutableList.Builder<TyParam> acc = ImmutableList.builder();
    eat(Token.LT);
    OUTER:
    while (true) {
      ImmutableList<Anno> annotations = maybeAnnos();
      String name = eatIdent();
      ImmutableList<Tree> bounds = ImmutableList.of();
      if (token == Token.EXTENDS) {
        next();
        bounds = tybounds();
      }
      acc.add(new TyParam(position, name, bounds, annotations));
      switch (token) {
        case COMMA:
          eat(Token.COMMA);
          continue;
        case GT:
          next();
          break OUTER;
        default:
          throw error(token);
      }
    }
    return acc.build();
  }

  private ImmutableList<Tree> tybounds() {
    ImmutableList.Builder<Tree> acc = ImmutableList.builder();
    do {
      acc.add(classty());
    } while (maybe(Token.AND));
    return acc.build();
  }

  private ClassTy classty() {
    return classty(null);
  }

  private ClassTy classty(ClassTy ty) {
    return classty(ty, maybeAnnos());
  }

  private ClassTy classty(ClassTy ty, ImmutableList<Anno> typeAnnos) {
    int pos = position;
    do {
      String name = eatIdent();
      ImmutableList<Type> tyargs = ImmutableList.of();
      if (token == Token.LT) {
        tyargs = tyargs();
      }
      ty = new ClassTy(pos, Optional.fromNullable(ty), name, tyargs, typeAnnos);
    } while (maybe(Token.DOT));
    return ty;
  }

  private ImmutableList<Type> tyargs() {
    ImmutableList.Builder<Type> acc = ImmutableList.builder();
    eat(Token.LT);
    OUTER:
    do {
      ImmutableList<Anno> typeAnnos = maybeAnnos();
      switch (token) {
        case COND:
          {
            next();
            switch (token) {
              case EXTENDS:
                next();
                Type upper = referenceType(maybeAnnos());
                acc.add(
                    new WildTy(position, typeAnnos, Optional.of(upper), Optional.<Type>absent()));
                break;
              case SUPER:
                next();
                Type lower = referenceType(maybeAnnos());
                acc.add(
                    new WildTy(position, typeAnnos, Optional.<Type>absent(), Optional.of(lower)));
                break;
              case COMMA:
                acc.add(
                    new WildTy(
                        position, typeAnnos, Optional.<Type>absent(), Optional.<Type>absent()));
                continue OUTER;
              case GT:
              case GTGT:
              case GTGTGT:
                acc.add(
                    new WildTy(
                        position, typeAnnos, Optional.<Type>absent(), Optional.<Type>absent()));
                break OUTER;
              default:
                throw error(token);
            }
            break;
          }
        case IDENT:
        case BOOLEAN:
        case BYTE:
        case SHORT:
        case INT:
        case LONG:
        case CHAR:
        case DOUBLE:
        case FLOAT:
          acc.add(referenceType(typeAnnos));
          break;
        default:
          throw error(token);
      }
    } while (maybe(Token.COMMA));
    switch (token) {
      case GT:
        next();
        break;
      case GTGT:
        token = Token.GT;
        break;
      case GTGTGT:
        token = Token.GTGT;
        break;
      default:
        throw error(token);
    }
    return acc.build();
  }

  private Type referenceType(ImmutableList<Anno> typeAnnos) {
    Type ty;
    switch (token) {
      case IDENT:
        ty = classty(null, typeAnnos);
        break;
      case BOOLEAN:
        next();
        ty = new PrimTy(position, typeAnnos, TurbineConstantTypeKind.BOOLEAN);
        break;
      case BYTE:
        next();
        ty = new PrimTy(position, typeAnnos, TurbineConstantTypeKind.BYTE);
        break;
      case SHORT:
        next();
        ty = new PrimTy(position, typeAnnos, TurbineConstantTypeKind.SHORT);
        break;
      case INT:
        next();
        ty = new PrimTy(position, typeAnnos, TurbineConstantTypeKind.INT);
        break;
      case LONG:
        next();
        ty = new PrimTy(position, typeAnnos, TurbineConstantTypeKind.LONG);
        break;
      case CHAR:
        next();
        ty = new PrimTy(position, typeAnnos, TurbineConstantTypeKind.CHAR);
        break;
      case DOUBLE:
        next();
        ty = new PrimTy(position, typeAnnos, TurbineConstantTypeKind.DOUBLE);
        break;
      case FLOAT:
        next();
        ty = new PrimTy(position, typeAnnos, TurbineConstantTypeKind.FLOAT);
        break;
      default:
        throw error(token);
    }
    while (maybe(Token.LBRACK)) {
      eat(Token.RBRACK);
      ty = new ArrTy(position, typeAnnos, ty);
    }
    return ty;
  }

  private EnumSet<TurbineModifier> modifiers(ImmutableList.Builder<Anno> annos) {
    EnumSet<TurbineModifier> access = EnumSet.noneOf(TurbineModifier.class);
    while (true) {
      switch (token) {
        case PUBLIC:
          next();
          access.add(TurbineModifier.PUBLIC);
          break;
        case PROTECTED:
          next();
          access.add(TurbineModifier.PROTECTED);
          break;
        case PRIVATE:
          next();
          access.add(TurbineModifier.PRIVATE);
          break;
        case STATIC:
          next();
          access.add(TurbineModifier.STATIC);
          break;
        case ABSTRACT:
          next();
          access.add(TurbineModifier.ABSTRACT);
          break;
        case FINAL:
          next();
          access.add(TurbineModifier.FINAL);
          break;
        case NATIVE:
          next();
          access.add(TurbineModifier.NATIVE);
          break;
        case SYNCHRONIZED:
          next();
          access.add(TurbineModifier.SYNCHRONIZED);
          break;
        case TRANSIENT:
          next();
          access.add(TurbineModifier.TRANSIENT);
          break;
        case VOLATILE:
          next();
          access.add(TurbineModifier.VOLATILE);
          break;
        case STRICTFP:
          next();
          access.add(TurbineModifier.STRICTFP);
          break;
        case AT:
          next();
          annos.add(annotation());
          break;
        default:
          return access;
      }
    }
  }

  private ImportDecl importDeclaration() {
    boolean stat = maybe(Token.STATIC);

    ImmutableList.Builder<String> type = ImmutableList.builder();
    type.add(eatIdent());
    boolean wild = false;
    OUTER:
    while (maybe(Token.DOT)) {
      switch (token) {
        case IDENT:
          type.add(eatIdent());
          break;
        case MULT:
          eat(Token.MULT);
          wild = true;
          break OUTER;
        default:
          break;
      }
    }
    eat(Token.SEMI);
    return new ImportDecl(position, type.build(), stat, wild);
  }

  private PkgDecl packageDeclaration(ImmutableList<Anno> annos) {
    PkgDecl result = new PkgDecl(position, qualIdent(), annos);
    eat(Token.SEMI);
    return result;
  }

  private ImmutableList<String> qualIdent() {
    ImmutableList.Builder<String> name = ImmutableList.builder();
    name.add(eatIdent());
    while (maybe(Token.DOT)) {
      name.add(eatIdent());
    }
    return name.build();
  }

  private Anno annotation() {
    ImmutableList<String> name = qualIdent();

    ImmutableList.Builder<Expression> args = ImmutableList.builder();
    if (token == Token.LPAREN) {
      eat(LPAREN);
      while (token != RPAREN) {
        ConstExpressionParser cparser = new ConstExpressionParser(lexer, token);
        args.add(cparser.expression());
        token = cparser.token;
        if (!maybe(COMMA)) {
          break;
        }
      }
      eat(Token.RPAREN);
    }

    return new Anno(position, name, args.build());
  }

  private String eatIdent() {
    String value = lexer.stringValue();
    eat(Token.IDENT);
    return value;
  }

  private void eat(Token kind) {
    if (token != kind) {
      throw error("expected %s, was %s", kind, token);
    }
    next();
  }

  private boolean maybe(Token kind) {
    if (token == kind) {
      next();
      return true;
    }
    return false;
  }

  TurbineError error(Token token) {
    String message;
    switch (token) {
      case IDENT:
        message = String.format("unexpected identifier '%s'", lexer.stringValue());
        break;
      default:
        message = String.format("unexpected token %s", token);
        break;
    }
    return TurbineError.format(lexer.source(), lexer.position(), message, new Object[] {});
  }

  private TurbineError error(String message, Object... args) {
    return TurbineError.format(lexer.source(), lexer.position(), message, args);
  }
}
