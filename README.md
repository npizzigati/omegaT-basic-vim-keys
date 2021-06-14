# OmegaT-basic-vim-keys
Vim-style modal editing for [OmegaT](https://omegat.org/)

## What works
- Most basic commands in insert and normal modes, including movement forward and backwards, deletion and insertion.

- Basic port of Justin Keyes' Sneak plugin, bound to s (forward sneak) and S (backword sneak), followed by the two characters to sneak to. Also works prefixed with d, to delete up to sneak location.

## What is not yet implemented
- Modes beside insert and normal
- Undo and redo (OmegaT's hotkeys can still be used for these)
- Configuration options
- Macros and mappings

## Installation
- Place the basic_vim_keys.groovy file in your OmegaT scripts directory and activate via the scripts menu.

## Exiting 
- After activating the script, you can return to OmegaT's normal editing mode by pressing ctrl-Q (ctrl-shift-q)

## Known Issues
- In projects with very large translation memories, the cursor will sometimes disappear. This can usually be mitigated by increasing the memory available to OmegaT via the startup command. See the section on launch command arguments in the OmegaT [documentation](https://omegat.sourceforge.io/manual-standard/en/chapter.installing.and.running.html#d0e596).
