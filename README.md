# saf-sftp

Access SFTP from Android Storage Access Framework (SAF)

## Implemented features

- Traverse tree, including symlinks
- Create, read and write files
- Delete files (but not directories yet)
- Metadata extraction
	- EXIF from images
	- MediaMetadataRetriever for audio/video
- Thumbnails from EXIF

## Target

This project uses SFTP v3 (-02 of the protocol draft), and is developed with OpenSSH and its `sftp-server`.

This project also make use of the following SFTP extensions:

- `space-available` or `statvfs@openssh.com`
	- To report capacity of roots, if available
- `fsync@openssh.com`
	- For fsync opened files, if available

This project also make use of the following server host environment features:

- DAC permission are considered on Linux
	- e.g. for reporting if file may be deleted
	- Relies on ability to read `/proc/self/status`
	- On other cases, it is treated as having all permissions and accesses are attempted
