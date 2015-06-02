package vizardous.trackmate.action;

import ij.IJ;
import ij.gui.Roi;
import ij.io.RoiEncoder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.codec.binary.Base64;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.traverse.DepthFirstIterator;

import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.TrackMate;
import fiji.plugin.trackmate.action.AbstractTMAction;
import fiji.plugin.trackmate.gui.TrackMateGUIController;
import fiji.plugin.trackmate.io.IOUtils;
import fiji.util.gui.GenericDialogPlus;

/**
 * A TrackMate action that exports the generated TrackMate Model to a pair of
 * PhyloXML and MetaXML.
 * 
 * @author Stefan Helfrich <s.helfrich@fz-juelich.de>
 * @version 0.2
 */
public class JungleExporter extends AbstractTMAction {
	
	private final TrackMateGUIController controller;
	private final GenericDialogPlus gd;
	private TrackMate trackmate;
	
	private Map<Spot, String> idMapping = new HashMap<Spot, String>();
	private int counter = 0;
	private Map<Double, Element> framesMap = new HashMap<Double, Element>();
	private double interval;
	
	private int populationCounter = 0;
	
	private String projectName;
	
	/*
	 * CONSTRUCTOR
	 */
	public JungleExporter(final TrackMateGUIController controller) {
		this.controller = controller;
		
		this.gd = new GenericDialogPlus("Export Settings");
		gd.addStringField("Project name", "default");
		gd.addNumericField("Imaging interval [min]", 8d, 0);
	}
	
	@Override
	public void execute(TrackMate trackmate) {
		this.execute(trackmate, (File) null);
	}
	
	public void execute(TrackMate trackmate, File filePhylo) {
		/*
		 * We need to add this directory field here, because we want to use the
		 * image folder as default location which is not known beforehand.
		 */
		String uiDestinationFolder = trackmate.getSettings().imageFolder;		
		gd.addDirectoryField("Destination", new File(uiDestinationFolder).exists() ? trackmate.getSettings().imageFolder : System.getProperty("user.dir"));
		
		gd.showDialog();
		if (gd.wasCanceled()){
			return;
		}
		
		logger.log("Exporting to format usable for visualization of master project.\n");
		this.trackmate = trackmate;
		
		this.projectName = gd.getNextString();
		
		final Model model = trackmate.getModel();		
		final int ntracks = model.getTrackModel().nTracks(true);
		if (ntracks == 0) {
			logger.log("No visible track found. Aborting.\n");
			return;
		}

		logger.log("  Preparing XML data.\n");
		
		Element[] rootElements = marshallModel(model);
		
		// Generate lineage tree in phyloXML format
		Element rootPhylo = rootElements[0];
		
		// Generate XML file containing meta data
		Element rootMeta = rootElements[1];
		
		// Determine folder/file for the lineage tree
		if (filePhylo == null) {
			// No file given -- ask for file
			File folder = new File(gd.getNextString());
			
			String filename = trackmate.getSettings().imageFileName;
			if (filename != null) {
				filename = filename.substring(0, filename.lastIndexOf("."));
				filePhylo = new File(folder.getPath() + File.separator + filename + ".xml");
			} else {
				filePhylo = new File(folder.getPath() + File.separator + "tree.xml");
				filePhylo = IOUtils.askForFileForSaving(filePhylo, controller.getGUI(), logger);
			}
		}
		
		// Derive meta data file from the selected file
		String phyloPath = filePhylo.getAbsolutePath();
		String metaPath = phyloPath.substring(0, phyloPath.lastIndexOf("."));
		File fileMeta = new File(metaPath + "_meta.xml");

		IJ.log("Writing phyloXML to "+filePhylo.getPath());
		logger.log("Writing phyloXML to "+filePhylo.getPath()+"\n");
		writeToFile(rootPhylo, filePhylo);
		
		IJ.log("Writing metaXML to "+fileMeta.getPath());
		logger.log("Writing metaXML to "+fileMeta.getPath()+"\n");
		writeToFile(rootMeta, fileMeta);
	}
	
	private void writeToFile(Element element, File file) {
		logger.log("  Writing metadata to file.\n");
		Document document = new Document(element);
		XMLOutputter outputter = new XMLOutputter(Format.getPrettyFormat());
		try {
			outputter.output(document, new FileOutputStream(file));
		} catch (FileNotFoundException e) {
			logger.error("Trouble writing to "+file+":\n" + e.getMessage());
		} catch (IOException e) {
			logger.error("Trouble writing to "+file+":\n" + e.getMessage());
		}
		logger.log("Done.\n");
	}
	
	/**
	 * This method creates the DOMs for both phyloXML and metaXML files in one
	 * run. In contrast to previous implementations, the integrated export of
	 * both files uses the property that {@link Clade}s and {@link Cells}
	 * originate from the same {@link Spot}.
	 * 
	 * @param model
	 *            The TrackMate {@link Model} that will be exported
	 * @return An array of DOM Elements. The first is the root Element of the
	 *         phyloXML, the second is the root of the metaXML.
	 */
	private Element[] marshallModel(final Model model) {
		Element rootPhylo = marshallPhylo();
		Element rootMeta = marshallMeta();		
		
		Set<Integer> trackIDs = model.getTrackModel().trackIDs(true);
		for (Integer id : trackIDs) {
			Element content = new Element(PHYLO_KEY, rootPhylo.getNamespace());
			
			// Add unique identifier for <phylogeny>
			Element phylogenyId = new Element(ID_KEY,rootPhylo.getNamespace());
			phylogenyId.setText(Integer.toString(id));
			content.addContent(phylogenyId);
			
			Set<Spot> track = model.getTrackModel().trackSpots(id);
			
			// Sort them by time to get the first one in the track
			TreeSet<Spot> sortedTrack = new TreeSet<Spot>(Spot.frameComparator);
			sortedTrack.addAll(track);
			
			// Small workaround to get the graph
			// Cast is necessary because getGraph() is not defined in the abstract class GraphIterator
			DepthFirstIterator<Spot, DefaultWeightedEdge> dfsIter = (DepthFirstIterator<Spot, DefaultWeightedEdge>) model.getTrackModel().getDepthFirstIterator(sortedTrack.first(), false);
			Graph<Spot, DefaultWeightedEdge> graph = dfsIter.getGraph();
			
			// create current spot
			Spot currentSpot = sortedTrack.first();
			
			/*
			 * PHYLOXML
			 */
			Element clade = marshallSpot(currentSpot, graph, rootPhylo.getNamespace(), rootMeta);
			content.addContent(clade);
			rootPhylo.addContent(content);
		}
		
		return new Element[]{rootPhylo, rootMeta};
	}
	
	/**
	 * Creates the scaffold of the PhyloXML.
	 * 
	 * @return The root element of the PhyloXML.
	 */
	private Element marshallPhylo() {
		Element root = new Element(PHYLOXML_KEY);
		
		/*
		 * Generate header with namespace declarations and schema location
		 */
		Namespace defaultNamespace = Namespace.getNamespace("http://www.phyloxml.org");
		root.setNamespace(defaultNamespace);
		
		Namespace xsi = Namespace.getNamespace("xsi", "http://www.w3.org/2001/XMLSchema-instance");
		root.addNamespaceDeclaration(xsi);
		root.setAttribute("schemaLocation", "http://www.phyloxml.org http://www.phyloxml.org/1.10/phyloxml.xsd", xsi);
		
		Namespace metaxmlNamespace = Namespace.getNamespace("metaxml", "http://13cflux.net/static/schemas/metaXML/2");
		root.addNamespaceDeclaration(metaxmlNamespace);
		
		/* Add projectName to PhyloXML */
		Element projectNameElement = new Element(PROJNAME_KEY, metaxmlNamespace);
		projectNameElement.setText(this.projectName);
		root.addContent(projectNameElement);
		
		return root;
	}	

	/**
	 * Generates the {@link Clade} for the provided Spot. This {@link Clade} has
	 * all children of Spot nested. It also generates and attaches {@link Cell}
	 * to the metaXML root element.
	 * 
	 * @param spot
	 *            The Spot that is to be exported
	 * @param graph
	 *            The graph from which to get the children of spot
	 * @param defaultNamespace
	 *            Namespace for the creation of {@link Element}s
	 * @param rootMeta
	 *            The root {@link Element} of the metaXML to which the generated
	 *            cells are attached.
	 * @return The clade Element for a spot.
	 */
	private Element marshallSpot(Spot spot, Graph<Spot, DefaultWeightedEdge> graph, Namespace defaultNamespace, Element rootMeta) {
		Element clade = new Element(CLADE_KEY, defaultNamespace);
		
		Element name = new Element(NAME_KEY, defaultNamespace);		
		
		// Recompute the IDs to avoid duplications
		name.setText(String.valueOf(counter));
		idMapping.put(spot, String.valueOf(counter++));	
		
		// TODO Compute the correct branch length
		Element branchLength = new Element(BRANCHLENGTH_KEY, defaultNamespace);
		branchLength.setText(DEFAULT_BRANCHLENGHT);
		
		clade.addContent(name);
		clade.addContent(branchLength);
		
		/*
		 * METAXML
		 */
		Element cellElement = generateCellForSpot(spot, rootMeta.getNamespace());
		Element frameElement = getFrameElement(spot, rootMeta.getNamespace(), rootMeta);		
		frameElement.addContent(cellElement);
		
		Set<DefaultWeightedEdge> edges = graph.edgesOf(spot);		
		for (DefaultWeightedEdge e : edges) {
			// We are only checking outgoing edges
			if (graph.getEdgeSource(e) == spot) {
				Spot child = graph.getEdgeTarget(e);
				
				// generate stuff for spot
				Element childClade = marshallSpot(child, graph, defaultNamespace, rootMeta);
				
				clade.addContent(childClade);
			}
		}
		
		return clade;
	}
	
	/**
	 * TODO Documentation.
	 * 
	 * @param spot
	 *            The Spot that is to be exported
	 * @param defaultNamespace
	 *            Namespace for the creation of {@link Element}s
	 * @return The cell Element for a spot.
	 */
	private Element generateCellForSpot(Spot spot, Namespace defaultNamespace) {
		// <cell ...>
		Element spotElement = new Element(CELL_KEY, defaultNamespace);
		spotElement.setAttribute(ID_ATT, idMapping.get(spot)==null?String.valueOf(counter++):idMapping.get(spot));
		
		// Cell features
		Map<String, Double> features = spot.getFeatures();
		
		// <center ...>
		Double centerX = features.get(Spot.POSITION_X);
		Double centerY = features.get(Spot.POSITION_Y);
//		Double centerZ = features.get(Spot.POSITION_Z);
		if (centerX != null && centerY != null) {
			Element centerElement = new Element(CENTER_KEY, defaultNamespace);
			spotElement.addContent(centerElement);
			
			// X coordinate
			Element centerXElement = new Element(X_KEY, defaultNamespace);
			centerXElement.setAttribute(UNIT_ATTR, "um");
			centerXElement.setText(String.format(Locale.US, "%.2f", centerX));
			centerElement.addContent(centerXElement);
			
			// Y coordinate
			Element centerYElement = new Element(Y_KEY, defaultNamespace);
			centerYElement.setAttribute(UNIT_ATTR, "um");
			centerYElement.setText(String.format(Locale.US, "%.2f", centerY));
			centerElement.addContent(centerYElement);
		}
		
		// <length ...>
		Double length = features.get(LENGTH);
		if (length != null && length > 0.0d) {
			Element lengthElement = new Element(LENGTH_KEY, defaultNamespace);
			lengthElement.setAttribute(UNIT_ATTR, "um");
			lengthElement.setText(String.format(Locale.US, "%.2f", length));
			spotElement.addContent(lengthElement);
		}
		
		// <area ...>
		Double area = features.get(AREA);
		if (area != null && area > 0.0d) {
			Element areaElement = new Element(AREA_KEY, defaultNamespace);
			areaElement.setAttribute(UNIT_ATTR, "um^2");
			areaElement.setText(String.format(Locale.US, "%.2f", area));
			spotElement.addContent(areaElement);
		}
		
		// <volume ...>
		// TODO Export (approximated) volume
		
		// multiple <fluorescence ...>			
		Element fluorescencesElement = new Element(FLUORESCENCES_KEY, defaultNamespace);
		
		// Keep it compatible to previous plugin versions where the feature was called YFP_FLUORESCENCE
		Double fluorescenceYFPMean = (features.get(YFP_FLUORESCENCE_MEAN) == null) ? features.get("YFP_FLUORESCENCE") : features.get(YFP_FLUORESCENCE_MEAN);
		if (fluorescenceYFPMean != null && fluorescenceYFPMean > 0.0d) {
			Element fluorescenceYFPElement = new Element(FLUOR_KEY, defaultNamespace);
			fluorescenceYFPElement.setAttribute(CHANNEL_ATTR, "yfp");
			
			Element fluorescenceYFPMeanElement = new Element(MEAN_KEY, defaultNamespace);
			fluorescenceYFPMeanElement.setAttribute(UNIT_ATTR, "au");
			fluorescenceYFPMeanElement.setText(String.format(Locale.US, "%.2f", fluorescenceYFPMean));
			fluorescenceYFPElement.addContent(fluorescenceYFPMeanElement);
			
			Double fluorescenceYFPStdDev = features.get(YFP_FLUORESCENCE_STDDEV);
			if (fluorescenceYFPStdDev != null) {
				Element fluorescenceYFPStdDevElement = new Element(STDDEV_KEY, defaultNamespace);
				fluorescenceYFPStdDevElement.setAttribute(UNIT_ATTR, "au");
				fluorescenceYFPStdDevElement.setText(String.format(Locale.US, "%.4f", fluorescenceYFPStdDev));
				fluorescenceYFPElement.addContent(fluorescenceYFPStdDevElement);
			}
			
			fluorescencesElement.addContent(fluorescenceYFPElement);
		}
		
		// Keep it compatible to previous plugin versions where the feature was called CRIMSON_FLUORESCENCE
		Double fluorescenceCrimson = (features.get(CRIMSON_FLUORESCENCE_MEAN) == null) ? features.get("CRIMSON_FLUORESCENCE") : features.get(CRIMSON_FLUORESCENCE_MEAN) ;
		if (fluorescenceCrimson != null && fluorescenceCrimson > 0.0d) {
			Element fluorescenceCrimsonElement = new Element(FLUOR_KEY, defaultNamespace);
			fluorescenceCrimsonElement.setAttribute(CHANNEL_ATTR, "crimson");
			
			Element fluorescenceCrimsonMeanElement = new Element(MEAN_KEY, defaultNamespace);
			fluorescenceCrimsonMeanElement.setAttribute(UNIT_ATTR, "au");
			fluorescenceCrimsonMeanElement.setText(String.format(Locale.US, "%.2f", fluorescenceCrimson));
			fluorescenceCrimsonElement.addContent(fluorescenceCrimsonMeanElement);
			
			Double fluorescenceCrimsonStdDev = features.get(CRIMSON_FLUORESCENCE_STDDEV);
			if (fluorescenceCrimsonStdDev != null) {
				Element fluorescenceCrimsonStdDevElement = new Element(STDDEV_KEY, defaultNamespace);
				fluorescenceCrimsonStdDevElement.setAttribute(UNIT_ATTR, "au");
				fluorescenceCrimsonStdDevElement.setText(String.format(Locale.US, "%.4f", fluorescenceCrimsonStdDev));
				fluorescenceCrimsonElement.addContent(fluorescenceCrimsonStdDevElement);
			}
			
			fluorescencesElement.addContent(fluorescenceCrimsonElement);
		}
		
		/* Check if at least one fluorescence needs to be exported */
		if ((fluorescenceYFPMean != null && fluorescenceYFPMean > 0.0d) || (fluorescenceCrimson != null && fluorescenceCrimson > 0.0d)) {
			spotElement.addContent(fluorescencesElement);
		}
		
		return spotElement;
	}
	
	/**
	 * Creates the scaffold of the MetaXML.
	 * 
	 * @return The root element of the MetaXML.
	 */
	private Element marshallMeta() {
		Element root = new Element(METAINFO_KEY);
		
		/*
		 * Generate header with namespace declarations and schema location
		 */
		Namespace defaultNamespace = Namespace.getNamespace("http://13cflux.net/static/schemas/metaXML/2");
		root.setNamespace(defaultNamespace);
		
		Namespace xsi = Namespace.getNamespace("xsi", "http://www.w3.org/2001/XMLSchema-instance");
		root.addNamespaceDeclaration(xsi);
		root.setAttribute("schemaLocation", "http://13cflux.net/static/schemas/metaXML/2 metaXML-2.6.0.xsd", xsi);
		
		Element projectNameElement = new Element(PROJNAME_KEY, defaultNamespace);
		projectNameElement.setText(this.projectName);
		root.addContent(projectNameElement);
		
		/*
		 * Calculate the experiments duration
		 */
		interval = gd.getNextNumber();
		// We could also use the calibration from the TrackMate setting. The
		// problem, however, is that we can not determine if the set value is
		// meaningful (TM will always set a value for dt).
//		double interval = trackmate.getSettings().dt;
		
		int frameNumber = trackmate.getSettings().nframes;
//		int frameNumber = trackmate.getSettings().tend;
		double duration = (frameNumber - 1) * interval;
		
		Element expDurationElement = new Element(EXPDUR_KEY, defaultNamespace);
		// Force "correct" formatting
		expDurationElement.setAttribute(UNIT_ATTR, "min");
		expDurationElement.setText(String.format(Locale.US, "%.0f", duration));
		root.addContent(expDurationElement);
		
		return root;
	}
	
	/**
	 * Compute the frame Element that will surround the cell Element for the
	 * provided Spot. Either an existing frame will be returned or a new one
	 * will be created and returned.
	 * 
	 * @param spot
	 *            The spot for which to compute the fitting frame Element.
	 * @param defaultNamespace
	 *            Namespace for the creation of {@link Element}s
	 * @param root
	 *            The root element of the MetaXML to which new frame Elements
	 *            will be appended
	 * @return The frame Element in which the Spot's cell Element will be
	 *         located
	 */
	private Element getFrameElement(Spot spot, Namespace defaultNamespace, Element root) {
		Double frame = spot.getFeature(Spot.FRAME);
		
		if (framesMap.containsKey(frame)) {
			return framesMap.get(frame);
		} else {			
			// Generate <frame id="0">
			Element frameElement = new Element(FRAME_KEY, defaultNamespace);
			frameElement.setAttribute(ID_ATT, Integer.toString(frame.intValue()));
			
			/*
			 * Generate content
			 */
			Element elapsedTime = new Element(ELAPSEDTIME_KEY, defaultNamespace);
			double elpsdTime = frame * interval;  // frame is 0-based
			elapsedTime.setAttribute(UNIT_ATTR, "min");
			elapsedTime.setText(String.format(Locale.US, "%.0f", elpsdTime));
			frameElement.addContent(elapsedTime);
			
			/*
			 * TODO Background fluorescence
			 */
			// TODO Compute center of mass for all cells
			Double totalX = 0d;
			Double totalY = 0d;
			int count = 0;			
			Iterator<Spot> iter = trackmate.getModel().getSpots().iterator(0, true);
			while (iter.hasNext()) {
				Spot iterSpot = iter.next();
				
				totalX += iterSpot.getFeatures().get(Spot.POSITION_X);
				totalY += iterSpot.getFeatures().get(Spot.POSITION_Y);
				count++;
			}
			
			Double centerX = totalX/count;
			Double centerY = totalY/count;
			
			// <population>
			Element populationElement = new Element(POPULATION_KEY, defaultNamespace);
			populationElement.setAttribute(ID_ATT, String.valueOf(populationCounter++));
			frameElement.addContent(populationElement);
			
			Element centerElement = new Element(CENTER_KEY, defaultNamespace);
			populationElement.addContent(centerElement);
			
			// X coordinate
			Element centerXElement = new Element(X_KEY, defaultNamespace);
			centerXElement.setAttribute(UNIT_ATTR, "um");
			centerXElement.setText(String.format(Locale.US, "%.2f", centerX));
			centerElement.addContent(centerXElement);
			
			// Y coordinate
			Element centerYElement = new Element(Y_KEY, defaultNamespace);
			centerYElement.setAttribute(UNIT_ATTR, "um");
			centerYElement.setText(String.format(Locale.US, "%.2f", centerY));
			centerElement.addContent(centerYElement);
			
			framesMap.put(frame, frameElement);
			root.addContent(frameElement);
			return frameElement;
		}
	}
	
	/*
	 * DEFAULT VALUES
	 */
	
	private static final String DEFAULT_BRANCHLENGHT = "1.000000e+00";
	
	
	/*
	 * XML KEYS
	 */
	private static final String PHYLOXML_KEY = "phyloxml";
	private static final String PHYLO_KEY = "phylogeny";
	private static final String CLADE_KEY = "clade";
	private static final String NAME_KEY = "name";
	private static final String BRANCHLENGTH_KEY = "branch_length";
	
	private static final String METAINFO_KEY = "metaInformation";
	private static final String PROJNAME_KEY = "projectName";
	private static final String EXPDUR_KEY = "experimentDuration";
	private static final String FRAME_KEY = "frame";
	private static final String ELAPSEDTIME_KEY = "elapsedTime";
	private static final String CELL_KEY = "cell";
	private static final String LENGTH_KEY = "length";
	private static final String AREA_KEY = "area";
	private static final String FLUORESCENCES_KEY = "fluorescences";
	private static final String FLUOR_KEY = "fluorescence";
	private static final String UNIT_ATTR = "unit";
	private static final String CHANNEL_ATTR = "channel";
	private static final String MEAN_KEY = "mean";
	private static final String STDDEV_KEY = "stddev";
	private static final String CENTER_KEY = "center";
	private static final String X_KEY = "x";
	private static final String Y_KEY = "y";
	private static final String POPULATION_KEY = "population";
	
	private static final String ID_ATT = "id";
	private static final String ID_KEY = "id";
		
	@Override
	public String toString() {
		return "Export to JuNGLE format";
	}

}