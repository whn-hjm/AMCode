/*
 * AMCode experimental "Android as Linux" platform shim.
 *
 * VS Code/code-server computes its extension target platform from
 * `process.platform`.  Node on Android reports "android", which code-server's
 * `getTargetPlatform()` maps to the synthetic value "web"; the server then
 * refuses to install/run non-Web extensions.  The official code-server Termux
 * workaround is to load this file with `NODE_OPTIONS=--require ...` and make
 * `process.platform` report "linux" before code-server starts:
 *
 *   Object.defineProperty(process, "platform", { get() { return "linux" } })
 *
 * This file is copied/extracted by CodeServerLauncher and is added to
 * NODE_OPTIONS by default (see CodeServerLauncher).  It is deliberately
 * defensive:
 *
 *  - A kill switch turns it off completely:
 *      $HOME/.amcode/disable-platform-shim
 *    or AMCODE_ANDROID_PLATFORM_SHIM=0.
 *  - It only applies to the code-server main process and the extension host.
 *    The file watcher, pty host, shared process, agent host and every process
 *    started from the integrated terminal (node/npm/npx/...) keep seeing
 *    platform "android".  This is important: if npm saw "linux" it would
 *    download glibc prebuilds that cannot run on bionic.
 *  - NODE_OPTIONS alone is not enough: VS Code removes NODE_OPTIONS (and
 *    VSCODE_NODE_OPTIONS) from the environment it gives the extension host
 *    (see `pu()` in server-main.js).  The main process therefore appends
 *    `--require <this file>` to `process.execArgv`; code-server forwards
 *    execArgv to its forked children, so the extension host loads the shim
 *    before its own code runs.  Helper processes load the shim too but return
 *    early, keeping platform=android.
 *  - Native extensions built for Linux (glibc) are still unsupported.  They
 *    may install after this shim, but dlopen() will fail on Android.  Only
 *    pure JavaScript extensions and extensions whose native parts are built
 *    for Android/bionic can work.
 *
 * Kill switch / override (evaluated by CodeServerLauncher):
 *   disable: $HOME/.amcode/disable-platform-shim (wins over everything)
 *            or app-private files/.amcode-disable-platform-shim
 *            or AMCODE_ANDROID_PLATFORM_SHIM=0
 *   force-on: AMCODE_ANDROID_PLATFORM_SHIM=1 (unless a kill-switch file exists)
 *
 * See also: https://coder.com/docs/code-server/termux#many-extensions-including-language-packs-fail-to-install
 */
(function () {
	"use strict";

	if (process.platform !== "android") {
		return;
	}

	var flag = String(process.env.AMCODE_ANDROID_PLATFORM_SHIM || "").toLowerCase();
	if (flag === "0" || flag === "false" || flag === "off" || flag === "no") {
		return;
	}

	var argv = process.argv || [];
	var script = String(argv[1] || "");
	var joined = argv.join("\n");

	// Never change the platform of helper processes.  In particular the file
	// watcher must keep platform "android" so @parcel/watcher loads the
	// bionic build instead of the glibc prebuild (which needs libstdc++.so.6).
	if (joined.indexOf("--type=fileWatcher") !== -1 ||
		joined.indexOf("--type=ptyHost") !== -1 ||
		joined.indexOf("--type=sharedProcess") !== -1 ||
		joined.indexOf("--type=agentHost") !== -1) {
		return;
	}

	// Exact executable paths only: the wrapper/entry code-server process, or
	// the forked extension host.  An arbitrary `node .../foo/code-server/x.js`
	// or `npm` invocation must keep platform=android.
	var isCodeServerMain =
		/(^|[\/\\])code-server$/.test(script) ||
		/(^|[\/\\])code-server[\/\\]out[\/\\]node[\/\\]entry(\.js)?$/.test(script);
	var isExtensionHost =
		joined.indexOf("--type=extensionHost") !== -1 &&
		script.indexOf("bootstrap-fork") !== -1;

	if (!isCodeServerMain && !isExtensionHost) {
		return;
	}

	// VS Code deliberately strips NODE_OPTIONS from the environment of the
	// extension host (`pu()` in server-main.js), so a plain --require in
	// NODE_OPTIONS would only affect the main code-server process.  code-server
	// does however forward process.execArgv to forked children (extension host,
	// file watcher, pty host, ...).  Add --require there from the main process
	// so the extension host also loads this shim.  The type checks above keep
	// the helper processes on platform "android".
	if (isCodeServerMain) {
		try {
			var shimPath = (typeof __filename === "string" && __filename) ||
				(typeof module !== "undefined" && module && module.filename) || "";
			var execArgv = process.execArgv || [];
			var alreadyPresent = false;
			for (var i = 0; i < execArgv.length - 1; i++) {
				if ((execArgv[i] === "--require" || execArgv[i] === "-r") &&
					execArgv[i + 1] === shimPath) {
					alreadyPresent = true;
					break;
				}
			}
			if (shimPath && !alreadyPresent) {
				execArgv.push("--require", shimPath);
			}
		} catch (error) {
			/* best effort; the main process still gets the platform override */
		}
	}

	try {
		Object.defineProperty(process, "platform", {
			configurable: true,
			enumerable: true,
			get: function () {
				return "linux";
			},
		});
		process.env.AMCODE_ANDROID_PLATFORM_SHIM_ACTIVE = "1";
	} catch (error) {
		// Never prevent code-server from starting just because the shim failed.
		try {
			console.error("[amcode] android-as-linux shim failed: " + (error && error.message));
		} catch (_) {
			/* ignore */
		}
	}
})();
