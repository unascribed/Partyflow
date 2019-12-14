(function() {
	document.body.classList.add("js");
	var art = document.querySelector(".art");
	var replaceArt = document.querySelector(".replaceArt");
	replaceArt.addEventListener("change", function() {
		art.style.backgroundImage = "url("+URL.createObjectURL(replaceArt.files[0])+")";
	});
	if (replaceArt.files[0]) {
		art.style.backgroundImage = "url("+URL.createObjectURL(replaceArt.files[0])+")";
	}
})();
