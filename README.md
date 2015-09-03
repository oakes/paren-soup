## Introduction

A library providing a ClojureScript viewer and editor that you can embed in any website. No, that was not a complete sentence. And neither is this. Be a rebel, like me, by using paren-soup. CodeMirror is nice if you want to support other languages, but why would you? Here are the features:

* Syntax highlighting
* Rainbow delimiters
* Automatic indentation
* InstaREPL (à la Light Table)

[Try the demo](http://oakes.github.io/paren-soup). To use paren-soup in your own website, just go to the releases section and download the files. In your HTML, link to one of the CSS files and use the following markup:
```html
<div class=”paren-soup”>
  <div class=”instarepl”></div>
  <div class=”numbers”></div>
  <div class=”content” contenteditable=”true”>
    ; put any initial code here
    (println “Hello, world!”)
  </div>
</div>
<script type=”text/javascript” src=”paren-soup.js”></script>
```
<script type=”text/javascript” src=”paren-soup.js”></script>
If you just want a viewer, not an editor, leave out the contenteditable attribute. If you don’t want the instaREPL or line numbers, remove the relevant divs and they will not appear. To get the code out of the content element via JavaScript or ClojureScript, read its `textContent` property. There is no API to learn!

## Build Instructions

Run `boot dev` in the project root. This will build the editor and run a server on http://localhost:3000. To build a release version, run `boot build`.

## Licensing

All files that originate from this project are dedicated to the public domain. I would love pull requests, and will assume that they are also dedicated to the public domain.
