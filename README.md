# saf-sftp

[WIP] Access SFTP from Android Storage Access Framework (SAF)

## Current Features

* Traverse home tree
* Read file support

## Notes

This is a WIP, and the code is dirty and full of debugging lines.
Many things still doesn't work.

### Known Issues

* Open images with Google Gallery doesn't work properly.
	* It open the file multiple times and may stuck.

### What's tested and worked so far

* Listing files, changing directories
* Copy files out with DocumentsUI
* Streaming from DocumentsUI with VLC
