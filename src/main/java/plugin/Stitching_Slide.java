package plugin;

import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.plugin.PlugIn;
import ij.ImagePlus;
import ij.io.ImageWriter;
import ij.io.FileSaver;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.PrintWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;

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

import net.imglib2.util.Util;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;

import javax.imageio.ImageIO;
import stitching.CommonFunctions;


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

    public static boolean defaultSubpixelAccuracy = false;

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

    public static String defaultTileConfiguration = "TileConfiguration.registered.txt";


    @Override
    public void run( String arg0 ) 
    {
        // First dialog for choosing all the options
        final GenericDialogPlus gd = new GenericDialogPlus("Stitch Image Slides");
        
        gd.addChoice( "Fusion_method", CommonFunctions.fusionMethodListGrid, CommonFunctions.fusionMethodListGrid[ defaultFusionMethod ] );
        gd.addCheckbox( "Subpixel_accuracy", defaultSubpixelAccuracy );
        gd.addStringField( "Tile_configuration_filename", defaultTileConfiguration, 50 );

        gd.addDirectoryField( "Directory", defaultDirectory, 50 );

        gd.showDialog();

        if(gd.wasCanceled())
            return;

        // compute parameters
        // the general stitching parameters
        final StitchingParameters params = new StitchingParameters();
        params.fusionMethod = defaultFusionMethod = gd.getNextChoiceIndex();
        params.subpixelAccuracy = defaultSubpixelAccuracy = gd.getNextBoolean();

        String tileConfigurationFilename = gd.getNextString();

        String directory = gd.getNextString();
        inputDirectory = directory;

        params.outputDirectory = null; // otherwise the output is not given when calling fuse.

        // we need to set this
        params.channel1 = 0;
        params.channel2 = 0;
        params.timeSelect = 0;
        params.checkPeaks = 5;
        params.displayFusion = false;
        params.dimensionality = 2;

        params.xOffset = defaultxOffset;
        params.yOffset = defaultyOffset;

        params.timeSelect = defaultTimeSelect;

        // load all elements from the configuration file
        final ArrayList< ImageCollectionElement > elements;

        try{
            elements = loadTileConfiguration(directory + "/" + tileConfigurationFilename, directory);
        }
        catch (IOException e){
            return;
        }

        ImagePlus imp = null;
        boolean noOverlap = false;
        final ArrayList<InvertibleBoundable> models = new ArrayList< InvertibleBoundable >();
        final ArrayList<ImagePlus> images = new ArrayList<ImagePlus>();

        boolean is32bit = false;
        boolean is16bit = false;
        boolean is8bit = false;

        for ( final ImageCollectionElement element : elements )
        {
            final ImagePlus im = element.open(false);
            
            if ( im.getType() == ImagePlus.GRAY32 )
                is32bit = true;
            else if ( im.getType() == ImagePlus.GRAY16 )
                is16bit = true;
            else if ( im.getType() == ImagePlus.GRAY8 )
                is8bit = true;
            images.add(im);
            models.add((InvertibleBoundable)element.getModel());
        }

        if ( is32bit )
            imp = Fusion.fuse( new FloatType(), images, models, params.dimensionality, params.subpixelAccuracy, params.fusionMethod, null, noOverlap, false, params.displayFusion );
        else if ( is16bit )
            imp = Fusion.fuse( new UnsignedShortType(), images, models, params.dimensionality, params.subpixelAccuracy, params.fusionMethod, null, noOverlap, false, params.displayFusion );
        else if ( is8bit )
            imp = Fusion.fuse( new UnsignedByteType(), images, models, params.dimensionality, params.subpixelAccuracy, params.fusionMethod, null, noOverlap, false, params.displayFusion );
        else
            IJ.log( "Unknown image type for fusion." );
        
        IJ.log( "Finished fusion" );
        
        if ( imp != null )
        {
            IJ.log( "Generating final image ..." );
            new FileSaver( imp ).saveAsJpeg( this.inputDirectory + "/output.jpg" );

            imp.setTitle( "Fused" );
            imp.show();
        }
        else{
            IJ.log( "Imp is null" );
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

    protected ArrayList< ImageCollectionElement > loadTileConfiguration(final String filename, final String directory)
        throws IOException
    {
        final ArrayList< ImageCollectionElement > elements = new ArrayList< ImageCollectionElement >();
        ArrayList<String> lines = new ArrayList<String>();
        FileInputStream fis = null;
        String line;
        try {
            fis = new FileInputStream(filename);
            InputStreamReader isr = new InputStreamReader(fis);
            BufferedReader br = new BufferedReader(isr);

            while((line = br.readLine()) != null)
            {
                lines.add(line);
            }
        }
        catch (IOException e){
            IJ.log(e.getMessage());
        }
        finally {
            if(fis != null)
                fis.close();
        }

        int index = 0, counter = 0;
        line = lines.get(index);

        while(!line.equals("# Define the image coordinates") && index < lines.size() - 1)
        {
            line = lines.get(++index);
        }

        for(int i = index + 1; i < lines.size(); i++){
            String[] splits = lines.get(i).split(";");
            IJ.log( splits[0] );

            // get the image coordinate
            String pattern = "((-)?\\d+\\.\\d+)\\s*,\\s*((-)?\\d+\\.\\d+)";

            // Create a Pattern object
            Pattern r = Pattern.compile(pattern);

            // Now create matcher object.
            Matcher m = r.matcher(splits[2]);
            float xoffset = 0.0f, yoffset = 0.0f;
            if(m.find()){
                xoffset = Float.parseFloat(m.group(1));
                yoffset = Float.parseFloat(m.group(3));
                IJ.log( "xoffset: " + m.group(1) + ", yoffset: " + m.group(3) );
            }

            // element
            ImageCollectionElement element = new ImageCollectionElement( new File( directory, splits[0] ), counter++ ); 
            element.setDimensionality( 2 );
            TranslationModel2D model = new TranslationModel2D();
            model.set(xoffset, yoffset);
            element.setModel( model );
            element.setOffset( new float[]{ xoffset, yoffset } );
            
            elements.add( element );
        }

        return elements;
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
        //      choose2[ 0 ] = new String[]{ "Right & Down", "Left & Down", "Right & Up", "Left & Up" };
        //      choose2[ 1 ] = new String[]{ "Down & Right", "Down & Left", "Up & Right", "Up & Left" };
        //      choose2[ 2 ] = new String[]{ "Right & Down", "Left & Down", "Right & Up", "Left & Up" };
        //      choose2[ 3 ] = new String[]{ "Down & Right", "Down & Left", "Up & Right", "Up & Left" };
            
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
}
