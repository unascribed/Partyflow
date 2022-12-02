"use strict";
if (!window.crypto || !window.crypto.subtle) {
	document.getElementById("no-crypto-warning").style.display = 'block';
} else {
	// WHY CAN'T I JUST DO toString(16)
	// GOD I HATE JAVASCRIPT
	// THANK YOU https://stackoverflow.com/a/55200387 FOR GIVING ME A MILDLY EFFICIENT ANSWER
	// (THAT I DIDN'T HAVE TO WRITE MYSELF)
	var byteToHex = [];

	for (var n = 0; n <= 0xff; ++n) {
		var hexOctet = n.toString(16);
		if (hexOctet.length == 1) hexOctet = "0"+hexOctet;
		byteToHex.push(hexOctet);
	}
	

	function hex(arrayBuffer) {
		const buff = new Uint8Array(arrayBuffer);
		const hexOctets = new Array(buff.length);

		for (let i = 0; i < buff.length; ++i) {
			hexOctets[i] = byteToHex[buff[i]];
		}
		return hexOctets.join("");
	}
	
	/** @type {HTMLInputElement} */
	// @ts-ignore
	const confirmPass = document.getElementById("confirm-password");
	/** @type {HTMLInputElement} */
	// @ts-ignore
	const pass = document.getElementById("password");
	
	let checkConfirmPass;
	if (confirmPass) {
		checkConfirmPass = function() {
			if (pass.value !== confirmPass.value) {
				confirmPass.setCustomValidity("Does not match password");
			} else {
				confirmPass.setCustomValidity("");
			}
		}
		confirmPass.removeAttribute("name");
		confirmPass.addEventListener("input", function() {
			checkConfirmPass();
		});
	}
	
	/** @type {HTMLInputElement} */
	// @ts-ignore
	const hashedPass = document.getElementById("hashed-password");
	pass.removeAttribute("name");
	hashedPass.name = "hashed-password";
	var te = new TextEncoder();
	pass.addEventListener("input", function() {
		pass.setCustomValidity("Hashing...");
		crypto.subtle.digest('SHA-512', te.encode(pass.value)).then(function(ab) {
			var val = hex(ab);
			hashedPass.value = val;
			pass.setCustomValidity("");
		});
		if (confirmPass) {
			checkConfirmPass();
		}
	});
}