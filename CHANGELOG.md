# Changelog

All notable changes to Flix Launcher are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [1.0.9] - 2026-10-30

### Changed
- Home opens on what you were watching: pressing Home selects Resume on your latest Continue watching title, so one OK starts it. With nothing to resume, Home goes to your apps as before
- New setting, Home screen → Home opens on: "What you were watching" or "Your apps"
- Setup and settings are reworded around watching

## [1.0.8] - 2026-10-30

### Added
- Update ready: when a new version has downloaded, a glowing pill appears next to the quick buttons on the home screen; OK installs it
- Settings shows an Install card at the top while an update is ready

## [1.0.7] - 2026-10-30

### Fixed
- After an update the Home button could go to the stock launcher, because Android left Flix's Home service marked as crashed. Flix now checks it again shortly after every update and turns it back on

## [1.0.6] - 2026-10-30

### Added
- Works with the NuvioPlusIPTV fork (com.nuvio.livetv): resume, Continue watching, search and posters open in it

## [1.0.5] - 2026-10-30

### Added
- Weather city (Settings → Clock & weather): type your city when the automatic location is wrong; leave it empty to go back to automatic

### Fixed
- The TV no longer shows Android's update confirmation over the idle screen: idle updates install only when they need no confirmation

## [1.0.4] - 2026-10-30

### Changed
- New Netflix-style search (the home screen's 🔍): an on-screen keyboard driven by the remote on the left, results on the right that fill in as each addon answers. It searches your own addons first, then Cinemeta, and opens on titles recommended from your rows
- Library search (All apps) also searches your addons

### Fixed
- Posters whose image link is broken show a cover with the title's name instead of a blank card

## [1.0.3] - 2026-10-30

### Added
- Resume from the carousel: your latest Continue watching title leads it, with "Resume S2 E5 · 23 min left"
- Up next: a finished episode is replaced in Continue watching by the next one that has aired
- Voice search: the mic button in search, or hold OK on the Search button, finds films, shows and apps by voice
- Switch Nuvio profile from the avatar at the start of the top menu
- Updates itself from GitHub releases: downloaded in the background, installed when the TV is idle, or from Settings → About
- First-run setup: connect Nuvio, choose rows, make Home open Flix
- Themes: Cinema, Glass, Midnight and Minimal (Settings → Home screen)

### Fixed
- After a crash the Home button comes back to Flix within seconds instead of 30 minutes, and a banner offers the fix if it doesn't

## [1.0.2] - 2026-10-30

### Important
- Releases are now signed with a proper release key. 1.0.0 was signed with a debug key, so **uninstall 1.0.0 once** before installing 1.0.2. Later updates install over it normally.

### Added
- OK on a film (or a Continue watching episode) opens Nuvio's stream list directly

### Fixed
- Works with the Play Store version of Nuvio (com.nuvio.app), which showed "Install Nuvio"
- Crash when opening a title in Nuvio
- Left/Right at the end of the apps row, top menu or carousel buttons no longer jumps to another row
- Tiles no longer keep the poster shape for a few seconds while moving along a row
- Release builds can no longer be signed with the debug key by accident

## [1.0.1] - 2026-10-27

### Added
- Back up and restore all settings, favourites and addon rows (Settings → About)

### Fixed
- Phone remote now needs the code from the TV's QR link, accepts changes only as form posts, and limits connections
- Home key service no longer touches other apps' accessibility services, and stays off when you turn it off
- Trailers no longer stop or swap when moving quickly between titles; trailer ids from addons are validated
- Poster cards no longer pick up another row's details or size; clicked posters spring back
- Background video and photos stop fully when you leave the home screen
- Cached files are saved safely and downloads have size limits
- Nuvio: a rejected sign-in now shows as signed out; tokens are stored encrypted
- Plain-HTTP traffic is limited to Apple's aerial video server; addons must use https

## [1.0.0] - 2026-10-27

First public release.

### Added
- Favourites row and an apps grid on the home screen itself
- Film and show rows from Stremio-compatible addons, with Top 10, genre filter and a featured carousel (can be switched off)
- Continue watching from a Nuvio account, with progress on each card and on the selected card
- Trailers behind the selected title, with start delay and sound options
- About 270 aerial video backgrounds in five categories, and photo backgrounds from the Bing archive
- Clock, greeting, date, sunrise/sunset arc, weather and 3-day forecast
- Searchable settings
- Home key handling for Google TV

### Performance
- Icons redrawn once into GPU bitmaps: about 54 MB of memory on a 2 GB TV
- Favourite apps appear before the rest of the app list is drawn

[1.0.1]: https://github.com/HatriGt/flix-launcher/releases/tag/v1.0.1
[1.0.0]: https://github.com/HatriGt/flix-launcher/releases/tag/v1.0.0
