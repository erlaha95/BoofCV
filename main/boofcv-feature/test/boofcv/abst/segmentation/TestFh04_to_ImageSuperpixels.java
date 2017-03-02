/*
 * Copyright (c) 2011-2017, Peter Abeles. All Rights Reserved.
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

package boofcv.abst.segmentation;

import boofcv.factory.segmentation.ConfigFh04;
import boofcv.factory.segmentation.FactoryImageSegmentation;
import boofcv.struct.ConnectRule;
import boofcv.struct.image.GrayF32;
import boofcv.struct.image.GrayU8;
import boofcv.struct.image.ImageBase;
import boofcv.struct.image.ImageType;

/**
 * @author Peter Abeles
 */
public class TestFh04_to_ImageSuperpixels<T extends ImageBase<T>> extends GeneralImageSuperpixelsChecks<T> {
	public TestFh04_to_ImageSuperpixels() {
		super(ImageType.single(GrayU8.class),
				ImageType.single(GrayF32.class),
				ImageType.pl(3, GrayU8.class),
				ImageType.pl(3, GrayF32.class));
	}

	@Override
	public ImageSuperpixels<T> createAlg( ImageType<T> imageType ) {
		return FactoryImageSegmentation.fh04(new ConfigFh04(20,8, ConnectRule.FOUR), imageType);
	}
}