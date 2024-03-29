"use strict";
(async function() {
	function pad(n) {
		return (n+100).toString().substring(1);
	}
	function formatTime(time, template) {
		time = Math.floor(time);
		template = Math.floor(template) || time;
		let tseconds = template%60;
		let tminutes = Math.floor((template/60)%60);
		let thours = Math.floor((template/60/60));

		let seconds = time%60;
		let minutes = Math.floor((time/60)%60);
		let hours = Math.floor((time/60/60));
		if (thours > 0) {
			return hours+":"+pad(minutes)+":"+pad(seconds);
		} else {
			return minutes+":"+pad(seconds);
		}
	}
	document.body.classList.add("yesscript");

	document.querySelectorAll(".lightboxable").forEach((e) => {
		/** @type {HTMLInputElement} */
		const input = e.querySelector("input");
		/** @type {HTMLElement} */
		const subject = e.querySelector("input + *");
		/** @type {HTMLDivElement} */
		const shadow = e.querySelector(".shadow");
		input.addEventListener("click", () => {
			if (input.checked) {
				document.body.classList.add("shadowed");
				shadow.style.width = "100%";
				shadow.style.height = "100%";
				shadow.style.opacity = "1";
				input.checked = false;
				const bcl = subject.getBoundingClientRect();
				subject.style.position = "fixed";
				subject.style.transform = "translateX(-50%) translateY(-50%)";
				subject.style.top = (bcl.top+(bcl.height/2))+"px";
				subject.style.left = (bcl.left+(bcl.width/2))+"px";
				subject.style.width = bcl.width+"px";
				subject.style.height = bcl.height+"px";
				subject.getBoundingClientRect();
				if (!window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
					subject.style.transition = "top 0.6s, left 0.6s, width 0.6s, height 0.6s";
				}
				input.checked = true;
			} else {
				shadow.style.opacity = "0";
				setTimeout(() => {
					document.body.classList.remove("shadowed");
					shadow.style.width = null;
					shadow.style.height = null;
					subject.style.position = null;
					subject.style.transform = null;
					subject.style.transition = null;
					subject.style.top = null;
					subject.style.left = null;
					subject.style.width = null;
					subject.style.height = null;
				}, 600);
			}
		});
	});

	// R128 is -23, ReplayGain is -18, Apple Music is -16, Spotify/YouTube are -14
	// All of these are kinda quiet, and Bandcamp doesn't apply normalization *at all*, so albums
	// meant for Bandcamp sound very quiet with basically any amount of volume normalization
	// -12 is an arbitrarily chosen compromise
	const REFERENCE_LEVEL = -12;

	const data = document.currentScript.dataset;
	const loudness = Number(data.loudness);
	const relativeLoudness = REFERENCE_LEVEL-loudness;
	console.info("Album gain: "+relativeLoudness.toFixed(2)+" ("+REFERENCE_LEVEL+"dB reference level)");
	let rgPercent = Math.pow(10, relativeLoudness/10)
	if (!AudioContext && rgPercent > 1) {
		rgPercent = 1;
		console.warn("Can't make audio louder than 100% without Web Audio API, ignoring gain.");
	}

	function rg() {
		if (localStorage.getItem("replaygain") === "off") return 1;
		return rgPercent;
	}

	const formats = JSON.parse(data.formats);
	const tracks = JSON.parse(data.tracks);
	if (tracks.length === 0) return;
	
	const tracksHtml = document.querySelector("#tracks");
	if (tracksHtml && tracksHtml.dataset.auto === "true") {
		tracks.forEach((track) => {
			let ele = document.createElement("div");
			ele.innerHTML = `
			<div class="track">
				<button class="player-control play"></button>
				<a href="#" onclick="false">
					<span class="trackNumber"></span>
					<span class="title"></span>
					<span class="subtitle"></span>
				</a>
				<span class="track-duration"></span>
			</div>
			`;
			ele = ele.firstElementChild;
			ele.dataset.trackSlug = track.slug;
			ele.querySelector(".title").textContent = track.title;
			ele.querySelector(".subtitle").textContent = track.subtitle;
			tracksHtml.appendChild(ele);
		});
	}
	
	const firstTrack = tracks[0];
	const release = data.releaseSlug;
	const overrideSlug = data.overrideSlug;
	/** @type {HTMLDivElement} */
	const widget = document.querySelector("#player-widget");
	/** @type {HTMLButtonElement} */
	const skipPrev = widget.querySelector(".skip-prev");
	/** @type {HTMLButtonElement} */
	const skipNext = widget.querySelector(".skip-next");
	if (tracks.length === 1) {
		skipPrev.style.display = "none";
		skipNext.style.display = "none";
	}
	tracks.forEach((track, i) => {
		track.length = track.end-track.start;
		track.index = i;
		let ele = Array.prototype.slice.apply(document.querySelectorAll(".track")).find(e => e.dataset.trackSlug === track.slug);
		if (ele) {
			track.element = ele;
			track.button = ele.querySelector(".player-control");
			track.lyrics = ele.querySelector(".lyrics");
			ele.querySelector(".track-duration").textContent = formatTime(track.length);
		}
	});
	let maxVolume = 1;
	const audio = new Audio();
	
	let setVolume = (v) => {
		if (v > 1) v = 1;
		audio.volume = v;
	};
	let getVolume = () => audio.volume;
	
	if (AudioContext) {
		maxVolume = 1.5;
		const ctx = new AudioContext({latencyHint: "playback"});
		const src = ctx.createMediaElementSource(audio);
		const gain = ctx.createGain();
		src.connect(gain).connect(ctx.destination);
		setVolume = (v) => {
			gain.gain.value = v;
			audio.dispatchEvent(new CustomEvent("volumechange"));
		};
		getVolume = () => gain.gain.value;
	}
	
	setVolume(Number(localStorage.getItem("volume") || 1) * rg());
	let selectedFormat = null;
	for (let i = 0; i < formats.length; i++) {
		const fmt = formats[i];
		if (audio.canPlayType(fmt.mime) !== "") {
			console.debug("Trying "+fmt.name+"...");
			try {
				await new Promise((resolve, reject) => {
					const audio = new Audio();
					// __testtrack is one second of silence
					audio.src = "{{root}}transcode/track/__testtrack?format="+fmt.name;
					audio.addEventListener("canplay", () => {
						resolve(audio);
					});
					audio.addEventListener("error", (e) => {
						reject(e);
					});
					audio.load();
				});
				selectedFormat = fmt;
				console.info("Successfully loaded "+fmt.name);
				break;
			} catch (e) {
				console.error("Can't play media format "+fmt.name, e);
			}
		}
	}
	function trans(e, from, to, cb) {
		if (!e) return;
		if (!e.classList.contains(from)) return;
		e.classList.add("trans");
		setTimeout(() => {
			e.classList.remove(from);
			e.classList.add(to);
			e.classList.remove("trans");
			if (cb) cb();
		}, 200);
	}
	/** @type {HTMLDivElement} */
	const seekbar = widget.querySelector(".seekbar");
	/** @type {HTMLDivElement} */
	const seekbarPosition = seekbar.querySelector(".position");
	/** @type {HTMLDivElement} */
	const seekbarBuffered = seekbar.querySelector(".buffered");
	/** @type {HTMLDivElement} */
	const volbar = widget.querySelector(".volumebar");
	/** @type {HTMLDivElement} */
	const volbarPosition = volbar.querySelector(".position");
	/** @type {HTMLDivElement} */
	const voldrop = widget.querySelector(".voldrop");
	/** @type {HTMLDivElement} */
	const voldropContents = voldrop.querySelector(".contents");
	/** @type {SVGSVGElement} */
	const volicon = widget.querySelector(".volicon");
	/** @type {HTMLButtonElement} */
	const replaygain = widget.querySelector(".replaygain");
	/** @type {HTMLButtonElement} */
	const globalPlayPause = widget.querySelector(".play-toggle");
	/** @type {HTMLSpanElement} */
	const trackName = widget.querySelector(".track-name");
	/** @type {HTMLSpanElement} */
	const playPositionNow = widget.querySelector(".play-position .current");
	/** @type {HTMLSpanElement} */
	const playPositionTotal = widget.querySelector(".play-position .total");
	if (data.hideTitle === "true") {
		trackName.style.display = "none";
	}
	let storedVolume = 0;
	if (localStorage.getItem("replaygain") === "off") {
		replaygain.classList.remove("replaygain");
		replaygain.classList.add("replaygain-off");
	}
	let currentTrack = firstTrack;
	let mouseDownInSeekbar = false;
	let mouseDownInVolbar = false;
	function dragSeekbar(x) {
		let bcr = seekbar.getBoundingClientRect();
		x -= bcr.left;
		let p = x/bcr.width;
		if (p < 0) p = 0;
		if (p > 1) p = 1;
		audio.currentTime = currentTrack.start+(currentTrack.length*p);
	}
	function dragVolbar(y) {
		let bcr = volbar.getBoundingClientRect();
		y -= bcr.top;
		let p = y/bcr.height;
		if (p < 0) p = 0;
		if (p > 1) p = 1;
		p = 1-p;
		p *= maxVolume;
		if (maxVolume > 1 && Math.abs(p-1) < 0.05) p = 1; // snap to 100%
		setVolume(p*rg());
	}
	trackName.textContent = currentTrack.title;
	window.addEventListener("mouseup", (e) => {
		if (mouseDownInSeekbar) audio.play();
		voldrop.classList.remove("open");
		mouseDownInSeekbar = false;
		mouseDownInVolbar = false;
		if (mouseDownInSeekbar || mouseDownInVolbar) {
			e.preventDefault();
		}
	});
	seekbar.addEventListener("mousedown", (e) => {
		audio.pause();
		mouseDownInSeekbar = true;
		dragSeekbar(e.clientX);
		e.preventDefault();
	});
	volbar.addEventListener("mousedown", (e) => {
		mouseDownInVolbar = true;
		voldrop.classList.add("open");
		dragVolbar(e.clientY);
		e.preventDefault();
	});
	window.addEventListener("mousemove", (e) => {
		if (mouseDownInSeekbar) dragSeekbar(e.clientX);
		if (mouseDownInVolbar) dragVolbar(e.clientY);
	});
	voldrop.addEventListener("click", (e) => {
		e.preventDefault();
		if (getVolume() === 0) {
			setVolume(storedVolume);
		} else {
			storedVolume = getVolume();
			setVolume(0);
		}
	});
	voldropContents.addEventListener("click", (e) => {
		e.preventDefault();
		e.stopPropagation();
	});
	skipPrev.addEventListener("click", (e) => {
		e.preventDefault();
		if (currentTrack.index > 0) {
			audio.currentTime = tracks[currentTrack.index-1].start;
		}
	});
	skipNext.addEventListener("click", (e) => {
		e.preventDefault();
		if (currentTrack.index < tracks.length) {
			audio.currentTime = tracks[currentTrack.index+1].start;
			updateTime();
		}
	});
	replaygain.addEventListener("click", (e) => {
		e.preventDefault();
		if (localStorage.getItem("replaygain") === "off") {
			replaygain.classList.remove("replaygain-off");
			replaygain.classList.add("replaygain");
			localStorage.setItem("replaygain", "on");
			setVolume(getVolume() * rgPercent);
		} else {
			replaygain.classList.remove("replaygain");
			replaygain.classList.add("replaygain-off");
			localStorage.setItem("replaygain", "off");
			setVolume(getVolume() / rgPercent);
		}
	});
	updateSkipState();
	globalPlayPause.addEventListener("click", (e) => {
		e.preventDefault();
		if (audio.paused) {
			audio.play();
		} else {
			audio.pause();
		}
	});
	tracks.forEach((track) => {
		if (track.button) {
			track.button.addEventListener("click", (e) => {
				e.preventDefault();
				let paused = audio.paused;
				if (currentTrack.slug !== track.slug) {
					audio.currentTime = track.start;
					updateTime();
					paused = true;
				}
				if (paused) {
					audio.play();
				} else {
					audio.pause();
				}
				e.preventDefault();
			});
		}
	});
	audio.src = "{{root}}transcode/"+(release ? "release/"+release : "track/"+(overrideSlug || tracks[0].slug))+"?format="+selectedFormat.name;
	function updateBuffered() {
		if (audio.buffered.length > 0 && currentTrack !== null) {
			let end = audio.buffered.end(audio.buffered.length-1);
			let progress = (end-currentTrack.start)/currentTrack.length;
			if (progress < 0) progress = 0;
			if (progress > 1) progress = 1;
			seekbarBuffered.style.width = (progress*100)+"%";
		}
	}
	function set(e, clazz, v) {
		if (v) {
			e.classList.add(clazz);
		} else {
			e.classList.remove(clazz);
		}
	}
	function updateSkipState() {
		set(skipPrev, "disabled", currentTrack.index === 0);
		set(skipNext, "disabled", currentTrack.index === tracks.length-1);
	}
	let wantsLyrics = false;
	function updateTime() {
		let track = tracks.find((track) => track.start <= audio.currentTime && track.end > audio.currentTime);
		if (!track) return; // ????
		if (!currentTrack || track.slug !== currentTrack.slug) {
			let openLyrics = wantsLyrics;
			if (currentTrack) {
				trans(currentTrack.button, "pause", "play");
				if (currentTrack.lyrics) {
					if (currentTrack.lyrics.open) {
						openLyrics = true;
						currentTrack.lyrics.open = false;
					} else {
						wantsLyrics = false;
						openLyrics = false;
					}
				}
			}
			currentTrack = track;
			if (openLyrics) {
				if (track.lyrics) {
					track.lyrics.open = true;
				} else {
					wantsLyrics = true;
				}
			}
			trackName.textContent = track.title;
			trans(currentTrack.button, "play", "pause");
			updateSkipState();
		}
		let progress = (audio.currentTime-track.start)/track.length;
		seekbarPosition.style.width = (progress*100)+"%";
		playPositionTotal.textContent = formatTime(track.length);
		playPositionNow.textContent = formatTime(audio.currentTime-track.start, track.length);
		updateBuffered();
	}
	function updateVolume() {
		let v = getVolume()/rg();
		let clazz = "high";
		if (v < 0.01)  {
			clazz = "muted";
		} else if (v <= 1/3) {
			clazz = "low";
		} else if (v <= 2/3) {
			clazz = "medium";
		} else if (v > 1.2) {
			clazz = "danger";
		}
		volicon.classList.remove("danger");
		volicon.classList.remove("high");
		volicon.classList.remove("medium");
		volicon.classList.remove("low");
		volicon.classList.remove("muted");
		volicon.classList.add(clazz);
		let suffix = " (Normalized)";
		if (localStorage.getItem("replaygain") === "off") {
			suffix = "";
		}
		voldrop.title = "Volume: "+Math.floor(v*100)+"%"+suffix;
		volbarPosition.style.width = ((v/maxVolume)*100)+"%";
		localStorage.setItem("volume", String(v));
	}
	function onFrame() {
		updateTime();
		if (!audio.paused) requestAnimationFrame(onFrame);
	}
	audio.addEventListener("progress", updateBuffered);
	audio.addEventListener("timeupdate", updateTime);
	audio.addEventListener("canplay", () => {
		trans(globalPlayPause, "loading", "play");
	});
	audio.addEventListener("error", (e) => {
		console.error("Can't play", e);
	});
	audio.addEventListener("volumechange", updateVolume);
	audio.addEventListener("pause", (e) => {
		if (mouseDownInSeekbar) return;
		trans(globalPlayPause, "pause", "play");
		if (currentTrack) trans(currentTrack.button, "pause", "play");
	});
	audio.addEventListener("play", (e) => {
		if (mouseDownInSeekbar) return;
		trans(globalPlayPause, "play", "pause");
		if (currentTrack) trans(currentTrack.button, "play", "pause");
		requestAnimationFrame(onFrame);
	});
	updateVolume();
	updateTime();
	audio.load();
})();
