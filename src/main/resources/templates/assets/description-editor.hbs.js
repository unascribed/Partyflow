'use strict';
(function() {
	var target = document.getElementById("quillTarget");
	target.style.display = 'block';
	var name = target.getAttribute("name");
	// @ts-ignore
	var quill = new Quill('#quillTarget', {
		modules: {history: true, toolbar: true},
		theme: 'snow'
	});
	var editor = target.querySelector(".ql-editor");
	/** @type {HTMLTextAreaElement} */
	// @ts-ignore
	var desc = document.getElementById(name.replace(".html", ".md"));
	desc.name = name;
	desc.style.display = 'none';
	desc.id = name+"-shadow";
	desc.innerHTML = editor.innerHTML;
	quill.on('text-change', function(delta, oldDelta, source) {
		desc.innerHTML = editor.innerHTML;
	});
})();