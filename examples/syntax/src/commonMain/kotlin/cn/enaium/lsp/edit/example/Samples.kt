package cn.enaium.lsp.edit.example

/**
 * Sample source text per language, taken from the grammar test fixtures
 * in tree-sitter-languages-kmp (languages/<id>/sample.txt).
 */
object Samples {
    /** (language id, sample code). */
    val all: Map<String, String> = mapOf(
        "agda" to "module Main where\n\nopen import Data.Nat\n\nmain : Nat\nmain = suc (suc zero)\n",
        "bash" to "#!/usr/bin/env bash\n\nset -euo pipefail\n\nfor f in *.txt; do\n  echo \"Processing \$f\"\n  grep -q 'TODO' \"\$f\" && echo \"found\"\ndone\n",
        "c-sharp" to "using System;\nusing System.Collections.Generic;\n\nclass Program\n{\n    static void Main()\n    {\n        var list = new List<int> { 1, 2, 3 };\n        foreach (var n in list)\n        {\n            Console.WriteLine(n);\n        }\n    }\n}\n",
        "c" to "#include <stdio.h>\n\nint main(void) {\n    printf(\"hello, world\\n\");\n    return 0;\n}\n",
        "cpp" to "#include <iostream>\n#include <vector>\n\nint main() {\n    std::vector<int> v{1, 2, 3};\n    for (auto n : v) {\n        std::cout << n << std::endl;\n    }\n    return 0;\n}\n",
        "css" to "body {\n  margin: 0;\n  padding: 1rem;\n  background-color: #f0f0f0;\n}\n\n.card:hover {\n  transform: scale(1.02);\n}\n",
        "diff" to "--- a/file.txt\n+++ b/file.txt\n@@ -1,3 +1,4 @@\n-old line\n+new line\n same line\n",
        "embedded-template" to "<!DOCTYPE html>\n<html>\n<head>\n  <title><%= title %></title>\n</head>\n<body>\n  <% if (user) { %>\n    <p>Hello, <%= user.name %></p>\n  <% } %>\n</body>\n</html>\n",
        "glsl" to "#version 330 core\n\nin vec3 vColor;\nout vec4 fragColor;\n\nvoid main() {\n    fragColor = vec4(vColor, 1.0);\n}\n",
        "go" to "package main\n\nimport \"fmt\"\n\nfunc main() {\n\tnums := []int{1, 2, 3}\n\tfor _, n := range nums {\n\t\tfmt.Println(n)\n\t}\n}\n",
        "haskell" to "module Main where\n\nmain :: IO ()\nmain = mapM_ print [1 :: Int .. 10]\n",
        "html" to "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n  <meta charset=\"utf-8\">\n  <title>Sample</title>\n</head>\n<body>\n  <h1>Hello</h1>\n  <p>Sample content</p>\n</body>\n</html>\n",
        "java" to "package com.example;\n\nimport java.util.List;\nimport java.util.Map;\nimport java.util.HashMap;\n\npublic class HelloWorld {\n    private final String name;\n\n    public HelloWorld(String name) {\n        this.name = name;\n    }\n\n    public static void main(String[] args) {\n        Map<String, List<Integer>> map = new HashMap<>();\n        map.put(\"answer\", List.of(40, 2));\n        for (var entry : map.entrySet()) {\n            System.out.println(entry.getKey() + \" = \" + entry.getValue());\n        }\n    }\n\n    @Override\n    public String toString() {\n        return \"HelloWorld{\" + name + '}';\n    }\n}\n",
        "javascript" to "function greet(name) {\n  return `Hello, \${name}!`;\n}\n\nconst names = ['world', 'kotlin'];\nfor (const n of names) {\n  console.log(greet(n));\n}\n",
        "json" to "{\n  \"name\": \"sample\",\n  \"version\": \"1.0.0\",\n  \"dependencies\": {\n    \"kotlin\": \"^2.0.0\"\n  }\n}\n",
        "julia" to "function fib(n::Int)\n    n <= 1 && return n\n    return fib(n - 1) + fib(n - 2)\nend\n\nprintln([fib(i) for i in 1:10])\n",
        "kotlin" to "fun main() {\n    val numbers = listOf(1, 2, 3)\n    val doubled = numbers.map { it * 2 }\n    println(doubled)\n\n    data class Person(val name: String, val age: Int)\n    val p = Person(\"Kotlin\", 9)\n    println(p)\n}\n",
        "lua" to "local function fib(n)\n    if n <= 1 then return n end\n    return fib(n - 1) + fib(n - 2)\nend\n\nprint(fib(10))\n",
        "markdown" to "# Title\n\nSome **bold** and *italic* text with `code`.\n\n- item one\n- item two\n\n> a quote\n",
        "ocaml" to "let rec fib n =\n  if n <= 1 then n else fib (n - 1) + fib (n - 2)\n\nlet () = List.iter (fun i -> Printf.printf \"%d\\n\" (fib i)) [1; 2; 3; 4]\n",
        "php" to "<?php\n\nfunction greet(string \$name): string {\n    return \"Hello, \$name!\";\n}\n\n\$names = ['world', 'php'];\nforeach (\$names as \$name) {\n    echo greet(\$name) . \"\\n\";\n}\n",
        "properties" to "# Database connection settings\nhost=localhost\nport=5432\nusername = admin\n# enable verbose logging\nverbose=true\n",
        "python" to "def fib(n):\n    if n <= 1:\n        return n\n    return fib(n - 1) + fib(n - 2)\n\nprint([fib(i) for i in range(10)])\n",
        "regex" to "^(https?://)?([a-z0-9.-]+)\\.([a-z]{2,})(/\\S*)?\$\n",
        "ruby" to "def fib(n)\n  return n if n <= 1\n  fib(n - 1) + fib(n - 2)\nend\n\nputs (1..10).map { |i| fib(i) }.join(', ')\n",
        "rust" to "fn fib(n: u64) -> u64 {\n    match n {\n        0 | 1 => n,\n        _ => fib(n - 1) + fib(n - 2),\n    }\n}\n\nfn main() {\n    let v: Vec<u64> = (1..=10).map(fib).collect();\n    println!(\"{:?}\", v);\n}\n",
        "scala" to "object Main extends App {\n  def fib(n: Int): Int = n match {\n    case 0 | 1 => n\n    case _     => fib(n - 1) + fib(n - 2)\n  }\n  println((1 to 10).map(fib).mkString(\", \"))\n}\n",
        "smali" to ".class public Lcom/example/Hello;\n.super Ljava/lang/Object;\n\n.method public static main([Ljava/lang/String;)V\n    .locals 2\n\n    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;\n    const-string v1, \"Hello\"\n    invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V\n    return-void\n.end method\n",
        "toml" to "title = \"TOML Example\"\n\n[owner]\nname = \"Tom\"\ndob = 1979-05-27\n\n[database]\nserver = \"192.168.1.1\"\nports = [ 8001, 8001, 8002 ]\n",
        "tsx" to "import React, { useState } from 'react';\n\ninterface CounterProps {\n  initial: number;\n}\n\nexport const Counter: React.FC<CounterProps> = ({ initial }) => {\n  const [count, setCount] = useState(initial);\n  return <button onClick={() => setCount(count + 1)}>{count}</button>;\n};\n",
        "typescript" to "interface Greeting {\n  name: string;\n}\n\nfunction greet(g: Greeting): string {\n  return `Hello, \${g.name}!`;\n}\n\nconst items: Greeting[] = [{ name: 'world' }];\nconsole.log(items.map(greet));\n",
        "verilog" to "module counter(\n    input  wire clk,\n    input  wire rst_n,\n    output reg [7:0] count\n);\n\nalways @(posedge clk or negedge rst_n) begin\n    if (!rst_n)\n        count <= 8'b0;\n    else\n        count <= count + 1;\nend\n\nendmodule\n",
        "xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<bookstore>\n  <book category=\"children\">\n    <title lang=\"en\">Harry Potter</title>\n    <author>J.K. Rowling</author>\n    <price>29.99</price>\n  </book>\n</bookstore>\n",
        "yaml" to "name: sample\nversion: 1.0.0\nservices:\n  web:\n    image: nginx:latest\n    ports:\n      - \"8080:80\"\n",
    )
}
