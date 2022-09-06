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
		} else if (tminutes > 0) {
			return minutes+":"+pad(seconds);
		} else {
			return seconds.toString();
		}
	}
	document.body.classList.add("yesscript");

	document.querySelectorAll(".lightboxable").forEach((e) => {
		const input = e.querySelector("input");
		const subject = e.querySelector("input + *");
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
	console.info("Album gain: "+relativeLoudness.toFixed(2)+" (-12dB reference level)");
	const rgPercent = Math.pow(10, relativeLoudness/10)

	function rg() {
		if (localStorage.getItem("replaygain") === "off") return 1;
		return rgPercent > 1 ? 1 : rgPercent;
	}

	const formats = JSON.parse(data.formats);
	const tracks = JSON.parse(data.tracks);
	if (tracks.length === 0) return;
	const firstTrack = tracks[0];
	const release = data.releaseSlug;
	const widget = document.querySelector("#player-widget");
	const skipPrev = widget.querySelector(".skip-prev");
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
			ele.querySelector(".track-duration").textContent = formatTime(track.length);
		}
	});
	const audio = new Audio();
	audio.volume = Number(localStorage.getItem("volume") || 1) * rg();
	let selectedFormat = null;
	for (let i = 0; i < formats.length; i++) {
		const fmt = formats[i];
		if (audio.canPlayType(fmt.mime) !== "") {
			console.debug("Trying "+fmt.name+"...");
			try {
				await new Promise((resolve, reject) => {
					const audio = new Audio();
					// do decode testing with a track rather than the gapless file, as it's faster
					audio.src = "{{root}}transcode/track/"+firstTrack.slug+"?format="+fmt.name;
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
	const seekbar = widget.querySelector(".seekbar");
	const seekbarPosition = seekbar.querySelector(".position");
	const seekbarBuffered = seekbar.querySelector(".buffered");
	const volbar = widget.querySelector(".volumebar");
	const volbarPosition = volbar.querySelector(".position");
	const volicon = widget.querySelector(".volicon");
	const replaygain = widget.querySelector(".replaygain");
	const globalPlayPause = widget.querySelector(".play-toggle");
	const playPositionNow = widget.querySelector(".play-position .current");
	const playPositionTotal = widget.querySelector(".play-position .total");
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
	function dragVolbar(x) {
		let bcr = volbar.getBoundingClientRect();
		x -= bcr.left;
		let p = x/bcr.width;
		if (p < 0) p = 0;
		if (p > 1) p = 1;
		audio.volume = p*rg();
	}
	window.addEventListener("mouseup", () => {
		if (mouseDownInSeekbar) audio.play();
		mouseDownInSeekbar = false;
		mouseDownInVolbar = false;
	});
	seekbar.addEventListener("mousedown", (e) => {
		audio.pause();
		mouseDownInSeekbar = true;
		dragSeekbar(e.clientX);
		e.preventDefault();
	});
	volbar.addEventListener("mousedown", (e) => {
		mouseDownInVolbar = true;
		dragVolbar(e.clientX);
		e.preventDefault();
	});
	window.addEventListener("mousemove", (e) => {
		if (mouseDownInSeekbar) dragSeekbar(e.clientX);
		if (mouseDownInVolbar) dragVolbar(e.clientX);
	});
	skipPrev.addEventListener("click", () => {
		if (currentTrack.index > 0) {
			audio.currentTime = tracks[currentTrack.index-1].start;
		}
	});
	skipNext.addEventListener("click", () => {
		if (currentTrack.index < tracks.length) {
			audio.currentTime = tracks[currentTrack.index+1].start;
			updateTime();
		}
	});
	replaygain.addEventListener("click", () => {
		if (localStorage.getItem("replaygain") === "off") {
			replaygain.classList.remove("replaygain-off");
			replaygain.classList.add("replaygain");
			localStorage.setItem("replaygain", "on");
			audio.volume *= rgPercent;
		} else {
			replaygain.classList.remove("replaygain");
			replaygain.classList.add("replaygain-off");
			localStorage.setItem("replaygain", "off");
			audio.volume /= rgPercent;
		}
	});
	updateSkipState();
	globalPlayPause.addEventListener("click", (e) => {
		if (audio.paused) {
			audio.play();
		} else {
			audio.pause();
		}
	});
	tracks.forEach((track) => {
		if (track.button) {
			track.button.addEventListener("click", (e) => {
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
	audio.src = "{{root}}transcode/"+(release ? "release/"+release : "track/"+tracks[0].slug)+"?format="+selectedFormat.name;
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
	function updateTime() {
		let track = tracks.find((track) => track.start <= audio.currentTime && track.end > audio.currentTime);
		if (!track) return; // ????
		if (!currentTrack || track.slug !== currentTrack.slug) {
			if (currentTrack) {
				trans(currentTrack.button, "pause", "play");
			}
			currentTrack = track;
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
		let v = audio.volume/rg();
		let clazz = "high";
		if (v <= 0)  {
			clazz = "muted";
		} else if (v <= 1/3) {
			clazz = "low";
		} else if (v <= 2/3) {
			clazz = "medium";
		}
		volicon.classList.remove("high");
		volicon.classList.remove("medium");
		volicon.classList.remove("low");
		volicon.classList.remove("muted");
		volicon.classList.add(clazz);
		volbarPosition.style.width = (v*100)+"%";
		localStorage.setItem("volume", v);
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
	});
	updateVolume();
	updateTime();
	audio.load();
})();
