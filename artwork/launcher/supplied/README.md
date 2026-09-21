# DAV Provider icon exports
Colors: field #0D0E12, cloud + letters #C7B9FF.
- svg/ — sources. 108 viewBox = adaptive icon canvas (safe zone: 66dp circle). ic_stat_dav + ic_contact are 24dp.
- png/ — playstore 512, foreground/monochrome 432 (xxxhdpi adaptive layer, transparent), background 432, stat + contact 96 (4x).
Note: the "DAV" letters in the SVGs use the Archivo 900 webfont via <text>; the PNGs have it baked in. For Android vector drawables, convert the text to paths first (or use the PNGs).