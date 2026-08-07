# Third-party notices

Clipify is proprietary — **All Rights Reserved** (see `LICENSE`): you may download and play with it, but not redistribute, repackage, or ship modified versions without permission. It relies on and/or downloads the
following third-party components, each under its own license.

## Bundled / compile-time dependencies

| Component | License | Notes |
| --- | --- | --- |
| [Fabric Loader](https://github.com/FabricMC/fabric-loader) | Apache-2.0 | Runtime mod loader. Not redistributed inside the mod jar. |
| [Fabric API](https://github.com/FabricMC/fabric-api) | Apache-2.0 | Required at runtime; user-installed. |
| [Yarn mappings](https://github.com/FabricMC/yarn) | CC0-1.0 | Used at build time only. |
| [Mod Menu](https://github.com/TerraformersMC/ModMenu) | MIT | Optional; `modCompileOnly`, not redistributed. |
| [SLF4J](https://www.slf4j.org/) | MIT | Provided by the Minecraft/Fabric runtime. |
| [Gson](https://github.com/google/gson) | Apache-2.0 | Provided by the Minecraft runtime. |
| [LWJGL / OpenGL & GLFW bindings](https://www.lwjgl.org/) | BSD-3-Clause | Provided by the Minecraft runtime. |

None of the above are shipped inside `clipify.jar`; they are resolved from the user's existing
Fabric/Minecraft installation or downloaded by their launcher.

## FFmpeg (downloaded at runtime, not bundled)

Clipify does **not** bundle FFmpeg in the mod jar. On first use it downloads a pre-built static
FFmpeg binary and verifies it against a hard-coded SHA-256 checksum before executing it. The pinned
builds are:

- **Windows x64 / Linux x64 / Linux arm64:** builds from
  [BtbN/FFmpeg-Builds](https://github.com/BtbN/FFmpeg-Builds), release tag
  `autobuild-2026-07-22-13-36` (FFmpeg 8.1 branch). These are **GPL** builds.
- **macOS:** build from [evermeet.cx](https://evermeet.cx/ffmpeg/) (FFmpeg 8.1.2).

FFmpeg is free software licensed under the **GNU General Public License (GPL) version 2 or later**
(the pinned builds are GPL because they include x264/x265). FFmpeg is a separate program executed as
a child process; it is neither linked into nor distributed with Clipify. Its source is available
from <https://ffmpeg.org/> and <https://github.com/BtbN/FFmpeg-Builds>.

- FFmpeg: <https://ffmpeg.org/>  — GPL-2.0-or-later
- x264: <https://www.videolan.org/developers/x264.html> — GPL-2.0-or-later
- x265: <https://www.x265.org/> — GPL-2.0-or-later

If you redistribute the FFmpeg binaries that Clipify downloads, you must comply with the GPL,
including making the corresponding source available. Clipify links to the upstream sources above
rather than redistributing the binaries.

## Trademarks

"Medal" is a trademark of Medal B.V. Clipify is an independent, unaffiliated project and is not
endorsed by or associated with Medal. The name is used only to describe the "save the last N
seconds" behaviour the mod imitates.
