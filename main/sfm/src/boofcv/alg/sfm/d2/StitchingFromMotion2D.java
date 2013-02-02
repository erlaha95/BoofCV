/*
 * Copyright (c) 2011-2013, Peter Abeles. All Rights Reserved.
 *
 * This file is part of BoofCV (http://boofcv.org).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package boofcv.alg.sfm.d2;

import boofcv.abst.sfm.d2.ImageMotion2D;
import boofcv.alg.distort.DistortImageOps;
import boofcv.alg.distort.ImageDistort;
import boofcv.alg.misc.GImageMiscOps;
import boofcv.struct.distort.PixelTransform_F32;
import boofcv.struct.image.ImageBase;
import georegression.struct.InvertibleTransform;
import georegression.struct.homo.Homography2D_F64;
import georegression.struct.point.Point2D_F64;
import georegression.struct.shapes.Rectangle2D_I32;

/**
 * Stitches together sequences of images using {@link ImageMotion2D}, typically used for image stabilization
 * and creating image mosaics.  Internally any motion model in the Homogeneous family can be used.  For convenience,
 * those models are converted into a {@link Homography2D_F64} on output.
 *
 * Sudden large motions between frames is often a sign that a bad solution was generated.  There for a maxJumpFraction
 * specifies how large of a jump is allowed relative to the image width/height.  If a large jump is detected
 * then {@link #process(boofcv.struct.image.ImageBase)} will return false.
 *
 * <p>
 * Developer Note:  The reason homogeneous transforms aren't used internally is that using that those transform can
 * be significantly slower when rendering an image than other simpler models.
 * </p>
 *
 * @author Peter Abeles
 */
public class StitchingFromMotion2D<I extends ImageBase, IT extends InvertibleTransform>
{
	// estimates image motion
	private ImageMotion2D<I,IT> motion;
	// renders the distorted image according to results from motion
	private ImageDistort<I> distorter;
	// converts different types of motion models into other formats
	private StitchingTransform<IT> converter;

	// initial transform, typical used to adjust the scale and translate
	private IT worldToInit;
	// size of the stitch image
	private int widthStitch, heightStitch;

	// very large motions are often signs that the estimated motion is wrong
	// this number defines the maximum allowed motion relative to image size
	private double maxJumpFraction;
	// image corners are used to detect large motions
	private Corners previousCorners = new Corners();
	private Corners currentCorners = new Corners();

	// storage for the transform from current frame to the initial frame
	private IT worldToCurr;

	private PixelTransform_F32 tranWorldToCurr;
	private PixelTransform_F32 tranCurrToWorld;

	// storage for the stitched image
	private I stitchedImage;
	private I workImage;

	// first time that it has been called
	private boolean first = true;

	/**
	 * TODO WRITE
	 *
	 * @param motion
	 * @param distorter
	 * @param converter
	 * @param maxJumpFraction
	 * @param worldToInit
	 * @param widthStitch
	 * @param heightStitch
	 */
	public StitchingFromMotion2D(ImageMotion2D<I, IT> motion,
								 ImageDistort<I> distorter,
								 StitchingTransform<IT> converter ,
								 double maxJumpFraction ,
								 IT worldToInit,
								 int widthStitch, int heightStitch)
	{
		this.motion = motion;
		this.distorter = distorter;
		this.converter = converter;
		this.maxJumpFraction = maxJumpFraction;
		this.worldToInit = (IT)worldToInit.createInstance();
		this.worldToInit.set(worldToInit);
		this.widthStitch = widthStitch;
		this.heightStitch = heightStitch;

		worldToCurr = (IT)worldToInit.createInstance();
	}

	/**
	 * Estimates the image motion and updates stitched image.  If it is unable to estimate the motion then false
	 * is returned and the stitched image is left unmodified. If false is returned then in most situations it is
	 * best to call {@link #reset()} and start over.
	 *
	 * @param image Next image in the sequence
	 * @return True if the stitched image is updated and false if it failed and was not
	 */
	public boolean process( I image ) {
		if( stitchedImage == null ) {
			stitchedImage = (I)image._createNew(widthStitch, heightStitch);
			workImage = (I)image._createNew(widthStitch, heightStitch);
		}

		if( motion.process(image) ) {
			update(image);

			// check to see if an unstable and improbably solution was generated
			return !checkLargeMotion(image.width, image.height);
		} else {
			return false;
		}
	}

	/**
	 * Throws away current results and starts over again
	 */
	public void reset() {
		GImageMiscOps.fill(stitchedImage, 0);
		motion.reset();
		worldToCurr.reset();
		first = true;
	}

	/**
	 * Looks for sudden large changes in corner location to detect motion estimation faults.
	 * @param width image width
	 * @param height image height
	 * @return true for fault
	 */
	private boolean checkLargeMotion( int width , int height ) {
		if( first ) {
			getImageCorners(width,height,previousCorners);
			first = false;
		} else {
			getImageCorners(width,height,currentCorners);

			// compute the maximum distance squared
			double threshold =  Math.max(width,height) * maxJumpFraction;
			threshold *= threshold;

			// see if any of the corners exceeded the threshold
			if( previousCorners.p0.distance2(currentCorners.p0) > threshold )
				return true;
			if( previousCorners.p1.distance2(currentCorners.p1) > threshold )
				return true;
			if( previousCorners.p2.distance2(currentCorners.p2) > threshold )
				return true;
			if( previousCorners.p3.distance2(currentCorners.p3) > threshold )
				return true;

			// make current into previous
			Corners tmp = currentCorners;
			currentCorners = previousCorners;
			previousCorners = tmp;
		}

		return false;

	}

	/**
	 * Adds the latest image into the stitched image
	 *
	 * @param image
	 */
	private void update(I image) {
		computeCurrToInit_PixelTran();

		// only process a cropped portion to speed up processing
		Rectangle2D_I32 box = DistortImageOps.boundBox(image.width, image.height,
				stitchedImage.width, stitchedImage.height, tranCurrToWorld);

		int x0 = box.tl_x;
		int y0 = box.tl_y;
		int x1 = box.tl_x + box.width;
		int y1 = box.tl_y + box.height;

		distorter.setModel(tranWorldToCurr);
		distorter.apply(image, stitchedImage,x0,y0,x1,y1);
	}

	private void computeCurrToInit_PixelTran() {
		IT initToCurr = motion.getFirstToCurrent();
		worldToInit.concat(initToCurr, worldToCurr);

		tranWorldToCurr = converter.convertPixel(worldToCurr,tranWorldToCurr);

		IT currToWorld = (IT) this.worldToCurr.invert(null);

		tranCurrToWorld = converter.convertPixel(currToWorld, tranCurrToWorld);
	}

	/**
	 * Sets the current image to be the origin of the stitched coordinate system.
	 * Must be called after {@link #process(boofcv.struct.image.ImageBase)}.
	 */
	public void setOriginToCurrent() {
		IT currToWorld = (IT)worldToCurr.invert(null);
		IT oldWorldToNewWorld = (IT)worldToInit.concat(currToWorld,null);

		PixelTransform_F32 newToOld = converter.convertPixel(oldWorldToNewWorld,null);

		// fill in the background color
		GImageMiscOps.fill(workImage, 0);
		// render the transform
		distorter.setModel(newToOld);
		distorter.apply(stitchedImage, workImage);

		// swap the two images
		I s = workImage;
		workImage = stitchedImage;
		stitchedImage = s;

		// have motion estimates be relative to this frame
		motion.setToFirst();
		first = true;
	}

	/**
	 * Returns the location of the current image's corners in the mosaic
	 *
	 * @return
	 */
	public Corners getImageCorners( int width , int height , Corners corners ) {

		if( corners == null )
			corners = new Corners();

		int w = width;
		int h = height;

		tranCurrToWorld.compute(0,0); corners.p0.set(tranCurrToWorld.distX, tranCurrToWorld.distY);
		tranCurrToWorld.compute(w,0); corners.p1.set(tranCurrToWorld.distX, tranCurrToWorld.distY);
		tranCurrToWorld.compute(w,h); corners.p2.set(tranCurrToWorld.distX, tranCurrToWorld.distY);
		tranCurrToWorld.compute(0,h); corners.p3.set(tranCurrToWorld.distX, tranCurrToWorld.distY);

		return corners;
	}

	/**
	 * Transform from world coordinate system into the current image frame.
	 *
	 * @return Transformation
	 */
	public Homography2D_F64 getWorldToCurr( Homography2D_F64 storage ) {
		return converter.convertH(worldToCurr,storage);
	}

	public IT getWorldToCurr() {
		return worldToCurr;
	}

	public I getStitchedImage() {
		return stitchedImage;
	}

	public static class Corners {
		Point2D_F64 p0 = new Point2D_F64();
		Point2D_F64 p1 = new Point2D_F64();
		Point2D_F64 p2 = new Point2D_F64();
		Point2D_F64 p3 = new Point2D_F64();
	}

}
