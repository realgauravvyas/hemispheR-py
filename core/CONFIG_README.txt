CONFIG PARAMETERS - Quick Reference

filename
  Path to your hemispherical canopy image. Supports jpg, png, tif.
  On Windows, use r"..." (raw string) so backslashes don't cause problems.

circ_mask (xc, yc, rc)
  This defines the circular mask that isolates the fisheye area in the image.
  xc and yc are the center coordinates (in pixels), and rc is the radius.
  You'll need to tweak these for each image or camera setup.
  If you set circ_mask to None, the code will try to auto-detect it
  (uses center of image and the largest circle that fits).

channel
  Picks which color channel to use for the canopy vs sky separation.
  Use 1 for Red, 2 for Green, 3 for Blue.
  Blue (3) works best for upward-facing canopy photos.
  For downward-facing images, you can use mixing formulas instead:
  "Luma", "2BG", "RGB", "GEI", "GLA", or "BtoRG"

circular
  Set to True for standard circular fisheye images (this is the usual case).
  Set to False if you have a full-frame fisheye image.

gamma
  Controls brightness correction. Default is 0.85.
  Less than 1 makes the image brighter, more than 1 makes it darker.
  Set to 1.0 if you don't want any correction.

stretch
  If True, applies a contrast stretch (clips the top and bottom 1% of pixel values).
  Usually you can leave this as False.

method
  The thresholding algorithm. Right now only "Otsu" is available.

zonal
  When False, one single threshold is computed for the whole image.
  When True, the image is split into 4 quadrants (N, E, S, W) and each
  gets its own threshold. Turn this on if your image has uneven lighting
  or bright spots on one side.

manual_threshold
  If you want to set the threshold yourself, put a number here (0 to 255).
  Leave it as None to let Otsu figure it out automatically.
  Note: you can't use this together with zonal=True.

maxVZA
  Maximum view zenith angle of your lens, in degrees.
  For a standard hemispherical lens (180 degree field of view), this is 90.

lens
  Which lens projection model to use.
  "equidistant" is a simple linear projection.
  "FC-E8" is for the Nikon FC-E8 fisheye converter (uses a polynomial formula).

startVZA
  Where to start the analysis, in degrees. Usually 0.

endVZA
  Where to stop the analysis, in degrees.
  70 is the standard if you're following the LAI-2000 setup.

nrings
  How many concentric rings to divide the hemisphere into.
  Default is 7 (gives you 10 degree steps from 0 to 70).

nseg
  How many azimuth slices. Default is 8 (each slice covers 45 degrees).

display
  Set to True if you want to see plots at each step (mask, threshold, rings).
  Set to False for faster runs with no plots - useful for batch processing.
  Matplotlib is only loaded when this is True, so it won't slow things down otherwise.

export_binary
  If True, saves the black-and-white (binarized) image into a "results" folder.
  Default is False.

verbose
  True prints extra details while running (like mask coordinates).
  False keeps the output minimal.
