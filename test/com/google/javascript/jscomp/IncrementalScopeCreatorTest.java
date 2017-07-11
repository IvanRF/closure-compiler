/*
 * Copyright 2017 The Closure Compiler Authors.
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

package com.google.javascript.jscomp;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;
import java.util.List;
import junit.framework.TestCase;

/**
 * A tests for {@link IncrementalScopeCreator}.
 */
public final class IncrementalScopeCreatorTest extends TestCase {

  public void testMemoization() throws Exception {
    Node root1 = IR.root();
    Node root2 = IR.root();
    Compiler compiler = new Compiler();
    compiler.initOptions(new CompilerOptions());
    ScopeCreator creator = IncrementalScopeCreator.getInstance(compiler).freeze();
    Scope scopeA = creator.createScope(root1, null);
    assertSame(scopeA, creator.createScope(root1, null));
    assertNotSame(scopeA, creator.createScope(root2, null));
  }

  public void testParialGlobalScopeRefresh() throws Exception {

    Compiler compiler = new Compiler();
    CompilerOptions options = new CompilerOptions();
    List<SourceFile> externs = ImmutableList.of(
        SourceFile.fromCode("externs.js", "var symbol;var ext"));
    List<SourceFile> srcs = ImmutableList.of(
        SourceFile.fromCode("testcode1.js", "var a; var b; function foo() {}"),
        SourceFile.fromCode("testcode2.js", "var x;"));
    ScopeCreator creator = IncrementalScopeCreator.getInstance(compiler).freeze();
    compiler.init(externs, srcs, options);
    compiler.parseInputs();

    checkState(!compiler.hasErrors());

    Node root = compiler.getRoot();
    Node fnFoo = findDecl(root, "foo");
    checkState(fnFoo.isFunction());

    Scope globalScope = creator.createScope(root, null);
    Scope globalFunction = creator.createScope(fnFoo, globalScope);

    assertTrue(globalScope.isDeclared("a", true));
    assertTrue(globalScope.isDeclared("b", true));
    assertTrue(globalScope.isDeclared("x", true));
    assertTrue(globalScope.isDeclared("ext", true));
    assertFalse(globalScope.isDeclared("nonexistant", true));

    // Make a change that affects the global scope (and report it)
    removeFirstDecl(compiler, compiler.getRoot(), "a");

    Scope globalScope2 = creator.createScope(compiler.getRoot(), null);
    assertSame(globalScope, globalScope2);
    // unchanged local scopes should be preserved
    assertSame(globalFunction, creator.createScope(fnFoo, globalScope));

    assertTrue(globalScope2.isDeclared("a", true)); // still declared, scope creator is frozen
    assertTrue(globalScope2.isDeclared("b", true));
    assertTrue(globalScope2.isDeclared("x", true));
    assertTrue(globalScope2.isDeclared("ext", true));
    assertFalse(globalScope2.isDeclared("nonexistant", true));

    // Allow the scopes to be updated by calling "thaw" and "freeze"

    IncrementalScopeCreator.getInstance(compiler).thaw();

    IncrementalScopeCreator.getInstance(compiler).freeze();

    Scope globalScope3 = creator.createScope(compiler.getRoot(), null);
    assertSame(globalScope, globalScope3);
    // unchanged local scopes should be preserved
    assertSame(globalFunction, creator.createScope(fnFoo, globalScope));

    assertFalse(globalScope3.isDeclared("a", true)); // no declared, scope creator has refreshed
    assertTrue(globalScope3.isDeclared("b", true));
    assertTrue(globalScope3.isDeclared("x", true));
    assertTrue(globalScope3.isDeclared("ext", true));
    assertFalse(globalScope3.isDeclared("nonexistant", true));

    IncrementalScopeCreator.getInstance(compiler).thaw();
  }

  public void testRefreshedGlobalScopeWithRedeclaration() throws Exception {

    Compiler compiler = new Compiler();
    CompilerOptions options = new CompilerOptions();
    List<SourceFile> externs = ImmutableList.of(
        SourceFile.fromCode("externs.js", ""));
    List<SourceFile> srcs = ImmutableList.of(
        SourceFile.fromCode("testcode1.js", "var a; var b;"),
        SourceFile.fromCode("testcode2.js", "var a;"));
    ScopeCreator creator = IncrementalScopeCreator.getInstance(compiler).freeze();
    compiler.init(externs, srcs, options);
    compiler.parseInputs();

    checkState(!compiler.hasErrors());

    Node root = compiler.getRoot();

    Scope globalScope = creator.createScope(root, null);

    assertTrue(globalScope.isDeclared("a", true));
    assertTrue(globalScope.isDeclared("b", true));

    removeFirstDecl(compiler, compiler.getRoot(), "a"); // leaves the second declaration
    removeFirstDecl(compiler, compiler.getRoot(), "b");

    // Allow the scopes to be updated by calling "thaw" and "freeze"

    IncrementalScopeCreator.getInstance(compiler).thaw();

    IncrementalScopeCreator.getInstance(compiler).freeze();

    Scope globalScope2 = creator.createScope(compiler.getRoot(), null);
    assertSame(globalScope, globalScope2);

    assertTrue(globalScope2.isDeclared("a", true)); // still declared in second file
    assertFalse(globalScope2.isDeclared("b", true));

    IncrementalScopeCreator.getInstance(compiler).thaw();
  }

  public void testPreconditionCheck() throws Exception {
    Compiler compiler = new Compiler();
    compiler.initOptions(new CompilerOptions());
    Node root = IR.root();
    ScopeCreator creator = IncrementalScopeCreator.getInstance(compiler).freeze();
    Scope scopeA = creator.createScope(root, null);

    try {
      creator.createScope(root, scopeA);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  private void removeFirstDecl(Compiler compiler, Node n, String name) {
    Node decl = findDecl(n, name);
    compiler.reportChangeToEnclosingScope(decl);
    decl.detach();
  }

  private Node findDecl(Node n, String name) {
    Node result = find(n, new NodeUtil.MatchNameNode(name), Predicates.<Node>alwaysTrue());
    return result.getParent();
  }

  /**
   * @return Whether the predicate is true for the node or any of its descendants.
   */
  private static Node find(Node node,
                     Predicate<Node> pred,
                     Predicate<Node> traverseChildrenPred) {
    if (pred.apply(node)) {
      return node;
    }

    if (!traverseChildrenPred.apply(node)) {
      return null;
    }

    for (Node c = node.getFirstChild(); c != null; c = c.getNext()) {
      Node result = find(c, pred, traverseChildrenPred);
      if (result != null) {
        return result;
      }
    }

    return null;
  }
}
