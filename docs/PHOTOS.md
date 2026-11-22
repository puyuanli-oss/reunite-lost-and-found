# Photo storage and privacy

Each report can have one photo. An authorized create/replace upload sends the raw image body to `POST /api/reports/{id}/photo`, with its actual `Content-Type`, the session's CSRF token, and `X-Report-Version` containing the current report version. Photo removal uses the same CSRF/version headers with `DELETE /api/reports/{id}/photo`. Successful photo changes increment the report version. The browser previews the selected file locally; that preview does not mean the server accepted or saved it. JPEG, PNG, and WebP are the intended input formats. The photo may be omitted, replaced, or removed.

`PhotoService.sanitize` rejects empty files, uploads over **10 MiB (10,485,760 bytes)**, unsupported signatures, MIME/content mismatches, failed decodes, and damaged images that produce decoder warnings. It reads image dimensions before allocating a decoded image and rejects widths/heights over 10,000 pixels or a total over 32 million pixels. The request handler must also bound the input stream before constructing its byte array.

The service decodes the first image, draws its pixels into a fresh image, and saves a newly encoded PNG. Transparency is retained. Original filenames, input bytes, EXIF/GPS, XMP, comments, and other source metadata are not persisted. Extra input bytes do not survive normalization. Animated input is normalized to its first decoded frame. Visual information printed in the image itself remains visible: users should crop or cover names, ID numbers, faces, or other sensitive content before uploading. A photo is never used as ownership proof or an automatic matching feature.

Normalized output is also bounded to 10 MiB. Some small JPEG/WebP uploads expand when encoded to PNG; these receive an actionable request to reduce the dimensions. SQL stores only the sanitized bytes, canonical `image/png` MIME type, byte count, report foreign key, and timestamp. This keeps the temporary database portable and avoids orphaned files or public filesystem paths.

## WebP runtime support

JPEG and PNG work with the standard JDK ImageIO providers. WebP additionally requires an ImageIO provider on the runtime classpath. Use `com.twelvemonkeys.imageio:imageio-webp:3.15.3` and its transitive dependencies. The implementation has no direct imports from that library; ImageIO discovers it through Java service providers. Keep the dependency JARs intact. If creating a shaded JAR, merge `META-INF/services` entries rather than replacing them.

Without a WebP provider, WebP uploads fail explicitly with “WebP decoding is unavailable on this server” and guidance to upload JPEG/PNG. The service never stores an unvalidated WebP or claims to have sanitized bytes it could not decode. The portable tests exercise this failure path; a full runtime acceptance test must also upload a real WebP when the provider has been installed.

Primary dependency documentation: [TwelveMonkeys ImageIO installation and service-provider guidance](https://github.com/haraldk/TwelveMonkeys#installing), [WebP artifact metadata](https://central.sonatype.com/artifact/com.twelvemonkeys.imageio/imageio-webp).

## Authorization contract

Both photo upload/removal and download must authorize the containing report **on every request**. This module permits only the owner of an open report to change its photo. Photo mutations lock and recheck the report's owner, open status, and version inside the database transaction; a pending ownership claim temporarily freezes photo changes. A private report's photo must be denied to anyone who cannot currently read the report, including someone who previously matched but whose lost report was removed or edited out of eligibility. Denials should use the same non-enumerating 404 as report details. Browser storage, guessed numeric IDs, old photo URLs, and UI-hidden buttons do not grant access. Responses should use `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`, and the stored canonical MIME type.

An invalid image maps to HTTP 400 with a safe validation message. A photo failure must leave the previous photo intact. A successful report save and an unsuccessful optional photo upload are separate outcomes; the interface must state which succeeded and allow a photo retry.
