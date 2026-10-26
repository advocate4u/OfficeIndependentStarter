# Office Independent

A free, independent Android office utility starter written in Kotlin and Jetpack Compose.

## Current features
- Open text, Markdown, and CSV files through Android's Storage Access Framework.
- Edit text and save back to the selected document or create a copy.
- Open PDFs and view pages using Android's built-in PdfRenderer, with previous/next navigation and zoom.
- Other document formats can be handed off to compatible apps installed on the device.

## Build APK
GitHub Actions runs on push and manual dispatch. Open **Actions**, select **Android build**, and download the `office-independent-debug-apk` artifact after a successful run.

## Limitations
This is not yet a full DOCX/XLSX/PPTX editor. Office engine integration, spreadsheet formulas, presentation editing, OCR, annotations, and persistent recent files remain future work. The source has not been tested until CI succeeds.

Original starter code is MIT-licensed. Any future third-party engine must be reviewed for its own license and attribution requirements.
