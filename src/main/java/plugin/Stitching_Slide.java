package plugin;

import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.plugin.PlugIn;
import ij.ImagePlus;
import ij.io.ImageWriter;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.PrintWriter;
import java.io.File;
import java.util.ArrayList;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

import mpicbg.models.Model;
import mpicbg.models.InvertibleBoundable;
import mpicbg.models.TranslationModel2D;
import mpicbg.stitching.StitchingParameters;
import mpicbg.stitching.fusion.Fusion;
import mpicbg.stitching.ImagePlusTimePoint;
import mpicbg.stitching.ImageCollectionElement;
import mpicbg.stitching.Downsampler;
import mpicbg.stitching.PairWiseStitchingImgLib;
import mpicbg.stitching.PairWiseStitchingResult;
import mpicbg.stitching.TextFileAccess;
import mpicbg.stitching.ComparePair;
import mpicbg.stitching.GlobalOptimization;

import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.util.Util;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;

import javax.imageio.ImageIO;

public class Stitching_Slide implements PlugIn
{
	/**
	 * Default Grid Size X and Y
	 */
	public static int defaultGridSizeX = 2; 
	public static int defaultGridSizeY = 3;

	/**
	 * Default overlap X
	 */
	public static double defaultOverlapX = 20;

	/**
	 * Default directory output
	 */
	public static String defaultDirectory = "";

	public static String defaultFileNames = "img_{iiiiii}.jpg";

	public static int defaultStartI = 1;

	public static int defaultFusionMethod = 0;

	public static double defaultR = 0.3;

	public static double defaultRegressionThreshold = 0.3;

	public static double defaultDisplacementThresholdRelative = 2.5;

	public static double defaultDisplacementThresholdAbsolute = 3.5;

	public static int defaultMemorySpeedChoice = 0;

	public static boolean defaultComputeOverlap = true;

	public static double defaultxOffset = 0, defaultyOffset = 0;	

	// current snake directions ( if necessary )
	// they need a global state
	int snakeDirectionX = 0; 
	int snakeDirectionY = 0;

	private String inputDirectory;

	public static int defaultTimeSelect = 1;

	public static String defaultTileConfiguration = "TileConfiguration.txt";


	@Override
	public void run( String arg0 ) 
	{
		final GridType grid = new GridType();
		final int gridType = grid.getType();
		final int gridOrder = grid.getOrder();

		if (gridType >= 5)
			return;

		// First dialog for choosing all the options
		final GenericDialogPlus gd = new GenericDialogPlus("Stitch Image Slides");
		
		// required options
		gd.addNumericField("Grid_size_x", defaultGridSizeX, 0 );
		gd.addNumericField("Grid_size_y", defaultGridSizeY, 0 );
		gd.addNumericField("Tile_overlap [%]", defaultOverlapX, 0);

		gd.addNumericField( "First_file_index_i", defaultStartI, 0 );
		gd.addDirectoryField( "Directory", defaultDirectory, 50 );
		gd.addStringField( "File_names for tiles", defaultFileNames, 50 );

		gd.showDialog();

		if(gd.wasCanceled())
			return;

		// compute parameters
		// the general stitching parameters
		final StitchingParameters params = new StitchingParameters();

		final int gridSizeX, gridSizeY;
		double overlapX, overlapY;


		gridSizeX = (int)Math.round(gd.getNextNumber());
		gridSizeY = (int)Math.round(gd.getNextNumber());
		overlapX = overlapY = gd.getNextNumber();

		int startI = defaultStartI = (int)Math.round(gd.getNextNumber());

		String directory = gd.getNextString();
		inputDirectory = directory;

		final String filenames = defaultFileNames = gd.getNextString();

		params.fusionMethod = defaultFusionMethod;
		params.regThreshold = defaultRegressionThreshold;
		params.relativeThreshold = defaultDisplacementThresholdRelative;		
		params.absoluteThreshold = defaultDisplacementThresholdAbsolute;
		params.computeOverlap = defaultComputeOverlap;
		params.cpuMemChoice = defaultMemorySpeedChoice;
		params.outputDirectory = null; // otherwise the output is not given when calling fuse.

		// we need to set this
		params.channel1 = 0;
		params.channel2 = 0;
		params.timeSelect = 0;
		params.checkPeaks = 5;

		params.xOffset = defaultxOffset;
		params.yOffset = defaultyOffset;

		params.timeSelect = defaultTimeSelect;

		// get all imagecollectionelements
		final ArrayList< ImageCollectionElement > elements;
		elements = getGridLayout( grid, gridSizeX, gridSizeY, overlapX, overlapY, directory, filenames, startI, 0, 0, params.virtual, null );

		// loading the images
		final ArrayList<ImagePlus> images = new ArrayList<ImagePlus>();

		for (final ImageCollectionElement element : elements)
		{
			long time = System.currentTimeMillis();
			final ImagePlus imp = element.open(params.virtual);
			
			time = System.currentTimeMillis() - time;
			
			if (imp == null)
				return;

			images.add(imp);
		}

		params.dimensionality = 2;
    	writeTileConfiguration(new File(directory, defaultTileConfiguration), elements);

		// compute and fuse
		ImagePlus ci = null;
		// ImagePlus result = performPairWiseStitching(images.get(0), images.get(1), params, this.inputDirectory);
		// ImagePlus ci = performPairWiseStitching(result, images.get(2), params, this.inputDirectory);
		for (final ImagePlus imp : images)
		{
			if(ci == null){
				ci = imp;
				continue;
			}

			ci = performPairWiseStitching(ci, imp, params, this.inputDirectory);;
		}

		if (ci != null)
		{
			IJ.log( "Generating final image ..." );
			Image img = ci.getImage();
            BufferedImage iimg = (BufferedImage)img;
            try
            {
                ImageIO.write(iimg, "png", new File(inputDirectory + "/output.png"));
            }
            catch(Exception ex)
            {
                System.out.println(ex.getMessage());
            }

			ci.setTitle("Fused");
			// ci.show();
		}

	}

	protected void writeTileConfiguration(final File file, final ArrayList<ImageCollectionElement> elements)
	{
    	// write the initial tileconfiguration
		final PrintWriter out = TextFileAccess.openFileWrite(file);
		final int dimensionality = elements.get(0).getDimensionality();
		
		out.println( "# Define the number of dimensions we are working on" );
        out.println( "dim = " + dimensionality );
        out.println( "" );
        out.println( "# Define the image coordinates" );
        
        for (final ImageCollectionElement element : elements)
        {
    		out.println(element.getFile().getName() + "; ; (" + element.getOffset(0) + ", " + element.getOffset(1) + ")");
        }

    	out.close();		
	}
	protected ArrayList< ImageCollectionElement > getGridLayout( final GridType grid, final int gridSizeX, final int gridSizeY, final double overlapX, final double overlapY, final String directory, final String filenames, 
			final int startI, final int startX, final int startY, final boolean virtual, final Downsampler ds )
	{
		final int gridType = grid.getType();
		final int gridOrder = grid.getOrder();

		// define the parsing of filenames
		// find how to parse
		String replaceX = "{", replaceY = "{", replaceI = "{";
		int numXValues = 0, numYValues = 0, numIValues = 0;

		int i1 = filenames.indexOf("{i");
		int i2 = filenames.indexOf("i}");
		if (i1 >= 0 && i2 > 0)
		{
			numIValues = i2 - i1;
			for (int i = 0; i < numIValues; i++)
				replaceI += "i";
			replaceI += "}";
		}
		else
		{
			replaceI = "\\\\\\\\";
		}

		// determine the layout
		final ImageCollectionElement[][] gridLayout = new ImageCollectionElement[ gridSizeX ][ gridSizeY ];
		
		// the current position[x, y] 
		final int[] position = new int[ 2 ];
		
		// we have gridSizeX * gridSizeY tiles
		for ( int i = 0; i < gridSizeX * gridSizeY; ++i )
		{
			// get the vector where to move
			getPosition( position, i, gridType, gridOrder, gridSizeX, gridSizeY );

			// get the filename
        	final String file = filenames.replace( replaceI, getLeadingZeros( numIValues, i + startI ) );
        	gridLayout[ position[ 0 ] ][ position [ 1 ] ] = new ImageCollectionElement( new File( directory, file ), i ); 
		}

		// based on the minimum size we will compute the initial arrangement
		int minWidth = Integer.MAX_VALUE;
		int minHeight = Integer.MAX_VALUE;
		int minDepth = Integer.MAX_VALUE;

		for ( int y = 0; y < gridSizeY; ++y )
			for ( int x = 0; x < gridSizeX; ++x )
			{
				IJ.log( "Loading (" + x + ", " + y + "): " + gridLayout[ x ][ y ].getFile().getAbsolutePath() + " ... " );

				long time = System.currentTimeMillis();
				final ImagePlus imp = gridLayout[ x ][ y ].open(false);

				time = System.currentTimeMillis() - time;
				
				if ( imp == null )
					return null;

				IJ.log( "" + imp.getWidth() + "x" + imp.getHeight() + "px, channels=" + imp.getNChannels() + ", timepoints=" + imp.getNFrames() + " (" + time + " ms)" );
				if ( imp.getWidth() < minWidth )
					minWidth = imp.getWidth();

				if ( imp.getHeight() < minHeight )
					minHeight = imp.getHeight();
				
				if ( imp.getNSlices() < minDepth )
					minDepth = imp.getNSlices();
			}

		final int dimensionality = 2;
		
		// now get the approximate coordinates for each element
		// that is easiest done incrementally
		int xoffset = 0, yoffset = 0, zoffset = 0;
    	
		// an ArrayList containing all the ImageCollectionElements
		final ArrayList< ImageCollectionElement > elements = new ArrayList< ImageCollectionElement >();
		
		for ( int y = 0; y < gridSizeY; y++ )
    	{
        	if ( y == 0 )
        		yoffset = 0;
        	else 
        		yoffset += (int)( minHeight * ( 1 - overlapY ) );

        	for ( int x = 0; x < gridSizeX; x++ )
            {
        		final ImageCollectionElement element = gridLayout[ x ][ y ];
        		
            	if ( x == 0 && y == 0 )
            		xoffset = yoffset = zoffset = 0;
            	
            	if ( x == 0 )
            		xoffset = 0;
            	else 
            		xoffset += (int)( minWidth * ( 1 - overlapX ) );
            	            	
            	element.setDimensionality( dimensionality );
            	element.setModel( new TranslationModel2D() );
            	element.setOffset( new float[]{ xoffset, yoffset } );
            	
            	elements.add( element );
            }
    	}
    	
    	return elements;
	}

	protected void getPosition( final int[] currentPosition, final int i, final int gridType, final int gridOrder, final int sizeX, final int sizeY )
	{
		// gridType: "Row-by-row", "Column-by-column", "Snake by rows", "Snake by columns", "Fixed position"
		// gridOrder:
		//		choose2[ 0 ] = new String[]{ "Right & Down", "Left & Down", "Right & Up", "Left & Up" };
		//		choose2[ 1 ] = new String[]{ "Down & Right", "Down & Left", "Up & Right", "Up & Left" };
		//		choose2[ 2 ] = new String[]{ "Right & Down", "Left & Down", "Right & Up", "Left & Up" };
		//		choose2[ 3 ] = new String[]{ "Down & Right", "Down & Left", "Up & Right", "Up & Left" };
			
		// init the position
		if ( i == 0 )
		{
			if ( gridOrder == 0 || gridOrder == 2 )
				currentPosition[ 0 ] = 0;
			else
				currentPosition[ 0 ] = sizeX - 1;
			
			if ( gridOrder == 0 || gridOrder == 1 )
				currentPosition[ 1 ] = 0;
			else
				currentPosition[ 1 ] = sizeY - 1;
			
			// it is a snake
			if ( gridType == 2 || gridType == 3 )
			{
				// starting with right
				if ( gridOrder == 0 || gridOrder == 2 )
					snakeDirectionX = 1;
				else // starting with left
					snakeDirectionX = -1;
				
				// starting with down
				if ( gridOrder == 0 || gridOrder == 1 )
					snakeDirectionY = 1;
				else // starting with up
					snakeDirectionY = -1;
			}
		}
		else // a move is required
		{
			// row-by-row, "Right & Down", "Left & Down", "Right & Up", "Left & Up"
			if ( gridType == 0 )
			{
				// 0="Right & Down", 2="Right & Up"
				if ( gridOrder == 0 || gridOrder == 2 )
				{
					if ( currentPosition[ 0 ] < sizeX - 1 )
					{
						// just move one more right
						++currentPosition[ 0 ];
					}
					else
					{
						// we have to change rows
						if ( gridOrder == 0 )
							++currentPosition[ 1 ];
						else
							--currentPosition[ 1 ];
						
						// row-by-row going right, so only set position to 0
						currentPosition[ 0 ] = 0;
					}
				}
				else // 1="Left & Down", 3="Left & Up"
				{
					if ( currentPosition[ 0 ] > 0 )
					{
						// just move one more left
						--currentPosition[ 0 ];
					}
					else
					{
						// we have to change rows
						if ( gridOrder == 1 )
							++currentPosition[ 1 ];
						else
							--currentPosition[ 1 ];
						
						// row-by-row going left, so only set position to 0
						currentPosition[ 0 ] = sizeX - 1;
					}					
				}
			}
			else if ( gridType == 1 ) // col-by-col, "Down & Right", "Down & Left", "Up & Right", "Up & Left"
			{
				// 0="Down & Right", 1="Down & Left"
				if ( gridOrder == 0 || gridOrder == 1 )
				{
					if ( currentPosition[ 1 ] < sizeY - 1 )
					{
						// just move one down
						++currentPosition[ 1 ];
					}
					else
					{
						// we have to change columns
						if ( gridOrder == 0 )
							++currentPosition[ 0 ];
						else
							--currentPosition[ 0 ];
						
						// column-by-column going down, so position = 0
						currentPosition[ 1 ] = 0;
					}
				}
				else // 2="Up & Right", 3="Up & Left"
				{
					if ( currentPosition[ 1 ] > 0 )
					{
						// just move one up
						--currentPosition[ 1 ];
					}
					else
					{
						// we have to change columns
						if ( gridOrder == 2 )
							++currentPosition[ 0 ];
						else
							--currentPosition[ 0 ];
						
						// column-by-column going up, so position = sizeY - 1
						currentPosition[ 1 ] = sizeY - 1;
					}
				}
			}
			else if ( gridType == 2 ) // "Snake by rows"
			{
				// currently going right
				if ( snakeDirectionX > 0 )
				{
					if ( currentPosition[ 0 ] < sizeX - 1 )
					{
						// just move one more right
						++currentPosition[ 0 ];
					}
					else
					{
						// just we have to change rows
						currentPosition[ 1 ] += snakeDirectionY;
						
						// and change the direction of the snake in x
						snakeDirectionX *= -1;
					}
				}
				else
				{
					// currently going left
					if ( currentPosition[ 0 ] > 0 )
					{
						// just move one more left
						--currentPosition[ 0 ];
						return;
					}
					// just we have to change rows
					currentPosition[ 1 ] += snakeDirectionY;
					
					// and change the direction of the snake in x
					snakeDirectionX *= -1;
				}
			}
			else if ( gridType == 3 ) // "Snake by columns" 
			{
				// currently going down
				if ( snakeDirectionY > 0 )
				{
					if ( currentPosition[ 1 ] < sizeY - 1 )
					{
						// just move one more down
						++currentPosition[ 1 ];
					}
					else
					{
						// we have to change columns
						currentPosition[ 0 ] += snakeDirectionX;
						
						// and change the direction of the snake in y
						snakeDirectionY *= -1;
					}
				}
				else
				{
					// currently going up
					if ( currentPosition[ 1 ] > 0 )
					{
						// just move one more up
						--currentPosition[ 1 ];
					}
					else
					{
						// we have to change columns
						currentPosition[ 0 ] += snakeDirectionX;
						
						// and change the direction of the snake in y
						snakeDirectionY *= -1;
					}
				}
			}
		}
	}

	public static String getLeadingZeros( final int zeros, final int number )
	{
		String output = "" + number;
		
		while (output.length() < zeros)
			output = "0" + output;
		
		return output;
	}

	public static ImagePlus performPairWiseStitching(final ImagePlus imp1, final ImagePlus imp2, final StitchingParameters params, final String inputDirectory)
	{
		final ArrayList<InvertibleBoundable> models = new ArrayList< InvertibleBoundable >();

		// the simplest case, only one registration necessary
		if ( imp1.getNFrames() == 1 || params.timeSelect == 0 )
		{
			// compute the stitching
			long start = System.currentTimeMillis();

			final PairWiseStitchingResult result;

			if ( params.computeOverlap )
			{
				result = PairWiseStitchingImgLib.stitchPairwise(imp1, imp2, imp1.getRoi(), imp2.getRoi(), 1, 1, params);
				IJ.log("shift (second relative to first): " + Util.printCoordinates( result.getOffset() ) + " correlation (R)=" + result.getCrossCorrelation() + " (" + (System.currentTimeMillis() - start) + " ms)");
				
				// update the dialog to show the numbers next time
				defaultxOffset = result.getOffset(0);
				defaultyOffset = result.getOffset(1);
			}
			else
			{
				final float[] offset;
				if (params.subpixelAccuracy)
					offset = new float[] { (float)params.xOffset, (float)params.yOffset };
				else
					offset = new float[] { Math.round( (float)params.xOffset ), Math.round( (float)params.yOffset ) };

				result = new PairWiseStitchingResult( offset, 0.0f, 0.0f );
				IJ.log( "shift (second relative to first): " + Util.printCoordinates( result.getOffset() ) + " (from dialog)");
			}

			for ( int f = 1; f <= imp1.getNFrames(); ++f )
			{
				TranslationModel2D model1 = new TranslationModel2D();
				TranslationModel2D model2 = new TranslationModel2D();
				model2.set(result.getOffset(0), result.getOffset(1));
				
				models.add(model1);
				models.add(model2);
			}
		}
		else
		{
			// get all that we have to compare
			final Vector<ComparePair> pairs = getComparePairs(imp1, imp2, params.dimensionality, params.timeSelect);

			// compute all compare pairs
			// compute all matchings
			final AtomicInteger ai = new AtomicInteger(0);

			final int numThreads;
			
			if (params.cpuMemChoice == 0)
				numThreads = 1;
			else
				numThreads = Runtime.getRuntime().availableProcessors();
			
	        final Thread[] threads = SimpleMultiThreading.newThreads(numThreads);
	    	
	        for (int ithread = 0; ithread < threads.length; ++ithread)
	            threads[ ithread ] = new Thread(new Runnable()
	            {
	                @Override
	                public void run()
	                {		
	                   	final int myNumber = ai.getAndIncrement();
	                 
	                    for (int i = 0; i < pairs.size(); i++)
	                    {
	                    	if (i % numThreads == myNumber)
	                    	{
	                    		final ComparePair pair = pairs.get(i);
	                    		
	                    		long start = System.currentTimeMillis();			

	            				final PairWiseStitchingResult result = PairWiseStitchingImgLib.stitchPairwise(pair.getImagePlus1(), pair.getImagePlus2(), 
	            						pair.getImagePlus1().getRoi(), pair.getImagePlus2().getRoi(), pair.getTimePoint1(), pair.getTimePoint2(), params);

	            				// only for 2D
	            				pair.setRelativeShift(new float[]{result.getOffset(0), result.getOffset(1)});	            				
	            				pair.setCrossCorrelation(result.getCrossCorrelation());

	            				IJ.log(pair.getImagePlus1().getTitle() + "[" + pair.getTimePoint1() + "]" + " <- " + pair.getImagePlus2().getTitle() + "[" + pair.getTimePoint2() + "]" + ": " + 
	            						Util.printCoordinates(result.getOffset()) + " correlation (R)=" + result.getCrossCorrelation() + " (" + (System.currentTimeMillis() - start) + " ms)");
	                    	}
	                    }
	                }
	            });
	        
	        SimpleMultiThreading.startAndJoin(threads);
			
	        // get the final positions of all tiles
			final ArrayList<ImagePlusTimePoint> optimized = GlobalOptimization.optimize(pairs, pairs.get(0).getTile1(), params);
			
			for (int f = 0; f < imp1.getNFrames(); ++f)
			{
				IJ.log (optimized.get(f*2 ).getImagePlus().getTitle() + "["+ optimized.get(f*2).getImpId() + "," + optimized.get(f*2).getTimePoint() + "]: " + optimized.get(f*2).getModel());
				IJ.log (optimized.get(f*2 + 1).getImagePlus().getTitle() + "["+ optimized.get(f*2 + 1).getImpId() + "," + optimized.get(f*2 + 1).getTimePoint() + "]: " + optimized.get(f*2 + 1).getModel());
				models.add((InvertibleBoundable)optimized.get(f*2).getModel());
				models.add((InvertibleBoundable)optimized.get(f*2 + 1).getModel());
			}
		}

		// now fuse
		IJ.log( "Fusing ..." );
		
		final ImagePlus ci;
		final long start = System.currentTimeMillis();			
			
		if (imp1.getType() == ImagePlus.GRAY32 || imp2.getType() == ImagePlus.GRAY32)
			ci = fuse(new FloatType(), imp1, imp2, models, params);
		else if (imp1.getType() == ImagePlus.GRAY16 || imp2.getType() == ImagePlus.GRAY16)
			ci = fuse(new UnsignedShortType(), imp1, imp2, models, params);
		else
			ci = fuse(new UnsignedByteType(), imp1, imp2, models, params);
		
		IJ.log( "Finished ... (" + (System.currentTimeMillis() - start) + " ms)");

		return ci;
	}

	protected static <T extends RealType<T> & NativeType<T>> ImagePlus fuse(final T targetType, final ImagePlus imp1, final ImagePlus imp2, final ArrayList<InvertibleBoundable> models, final StitchingParameters params)
	{
		final ArrayList<ImagePlus> images = new ArrayList<ImagePlus>();
		images.add( imp1 );
		images.add( imp2 );
		
		if (params.fusionMethod < 6)
		{
			ImagePlus imp = Fusion.fuse(targetType, images, models, params.dimensionality, params.subpixelAccuracy, params.fusionMethod, null, false, params.ignoreZeroValuesFusion, params.displayFusion);
			return imp;
		}
		else if (params.fusionMethod == 6) // overlay
		{
			// // images are always the same, we just trigger different timepoints
			// final InterpolatorFactory< FloatType, RandomAccessible< FloatType > > factory;
			
			// if ( params.subpixelAccuracy )
			// 	factory  = new NLinearInterpolatorFactory<FloatType>();
			// else
			// 	factory  = new NearestNeighborInterpolatorFactory< FloatType >();
		
			// // fuses the first timepoint but estimates the boundaries for all timepoints as it gets all models
			// final CompositeImage timepoint0 = OverlayFusion.createOverlay( targetType, images, models, params.dimensionality, 1, factory );
			
			// if ( imp1.getNFrames() > 1 )
			// {
			// 	final ImageStack stack = new ImageStack( timepoint0.getWidth(), timepoint0.getHeight() );
				
			// 	// add all slices of the first timepoint
			// 	for ( int c = 1; c <= timepoint0.getStackSize(); ++c )
			// 		stack.addSlice( "", timepoint0.getStack().getProcessor( c ) );
				
			// 	//"Overlay into composite image"
			// 	for ( int f = 2; f <= imp1.getNFrames(); ++f )
			// 	{
			// 		final CompositeImage tmp = OverlayFusion.createOverlay( targetType, images, models, params.dimensionality, f, factory );
					
			// 		// add all slices of the first timepoint
			// 		for ( int c = 1; c <= tmp.getStackSize(); ++c )
			// 			stack.addSlice( "", tmp.getStack().getProcessor( c ) );					
			// 	}
				
			// 	//convertXYZCT ...
			// 	ImagePlus result = new ImagePlus( params.fusedName, stack );
				
			// 	// numchannels, z-slices, timepoints (but right now the order is still XYZCT)
			// 	result.setDimensions( timepoint0.getNChannels(), timepoint0.getNSlices(), imp1.getNFrames() );
			// 	return CompositeImageFixer.makeComposite( result, CompositeImage.COMPOSITE );
			// }
			// else
			// {
			// 	timepoint0.setTitle( params.fusedName );
			// 	return timepoint0;
			// }
		}
		
		//"Do not fuse images"
		return null;
	}
	protected static Vector< ComparePair > getComparePairs( final ImagePlus imp1, final ImagePlus imp2, final int dimensionality, final int timeSelect )
	{
		final Model<?> model;
		
		model = new TranslationModel2D();

		final ArrayList<ImagePlusTimePoint> listImp1 = new ArrayList<ImagePlusTimePoint>();
		final ArrayList<ImagePlusTimePoint> listImp2 = new ArrayList<ImagePlusTimePoint>();
		
		for (int timePoint1 = 1; timePoint1 <= imp1.getNFrames(); timePoint1++)
			listImp1.add(new ImagePlusTimePoint(imp1, 1, timePoint1, model.copy(), null));

		for (int timePoint2 = 1; timePoint2 <= imp2.getNFrames(); timePoint2++)
			listImp2.add(new ImagePlusTimePoint( imp2, 2, timePoint2, model.copy(), null));
		
		final Vector<ComparePair> pairs = new Vector<ComparePair>();		
				
		// imp1 vs imp2 at all timepoints
		for (int timePointA = 1; timePointA <= Math.min(imp1.getNFrames(), imp2.getNFrames()); timePointA++)
		{
			ImagePlusTimePoint a = listImp1.get(timePointA - 1);
			ImagePlusTimePoint b = listImp2.get(timePointA - 1);
			pairs.add(new ComparePair(a, b));
		}

		if (timeSelect == 1)
		{
			// consequtively all timepoints of imp1
			for (int timePointA = 1; timePointA <= imp1.getNFrames() - 1; timePointA++)
				pairs.add(new ComparePair(listImp1.get(timePointA - 1), listImp1.get(timePointA + 1 - 1)));

			// consequtively all timepoints of imp2
			for (int timePointB = 1; timePointB <= imp2.getNFrames() - 1; timePointB++)
				pairs.add(new ComparePair(listImp2.get(timePointB - 1), listImp2.get(timePointB + 1 - 1)));		
		}
		else
		{
			// all against all for imp1
			for (int timePointA = 1; timePointA <= imp1.getNFrames() - 1; timePointA++)
				for (int timePointB = timePointA + 1; timePointB <= imp1.getNFrames(); timePointB++)
					pairs.add(new ComparePair(listImp1.get(timePointA - 1), listImp1.get(timePointB - 1)));
			
			// all against all for imp2
			for (int timePointA = 1; timePointA <= imp2.getNFrames() - 1; timePointA++)
				for (int timePointB = timePointA + 1; timePointB <= imp2.getNFrames(); timePointB++)
					pairs.add(new ComparePair(listImp2.get(timePointA - 1), listImp2.get(timePointB - 1)));
		}
		
		return pairs;
	}
}
