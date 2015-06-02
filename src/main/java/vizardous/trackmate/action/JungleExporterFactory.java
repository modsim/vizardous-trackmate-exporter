package vizardous.trackmate.action;

import javax.swing.ImageIcon;

import org.scijava.plugin.Plugin;

import fiji.plugin.trackmate.action.TrackMateAction;
import fiji.plugin.trackmate.action.TrackMateActionFactory;
import fiji.plugin.trackmate.gui.TrackMateGUIController;
import fiji.plugin.trackmate.gui.TrackMateWizard;

/**
 * TODO 
 * @author Stefan Helfrich <s.helfrich@fz-juelich.de>
 * @version 0.1
 */
@Plugin(type=TrackMateActionFactory.class)
public class JungleExporterFactory implements TrackMateActionFactory {
	
	public static final String KEY = "JUNGLE_EXPORTER";
	public static final ImageIcon ICON = new ImageIcon(TrackMateWizard.class.getResource("images/ISBIlogo.png"));
	public static final String NAME = "Export tracks to JuNGLE format";
	public static final String INFO_TEXT = "<html>Export the current model content to an XML file that can be "
			+ "viewed in the visualization tool developed in the master project of"
			+ "Stefan Helfrich."
			+ "<p> "
			+ "Both tracks and information about cells are exported."
			+ "</html>";
	
	@Override
	public TrackMateAction create(TrackMateGUIController controller) {
		// TODO Implement as singleton?
		return new JungleExporter(controller);
	}

	@Override
	public String getInfoText() {
		return INFO_TEXT;
	}

	@Override
	public ImageIcon getIcon() {
		return ICON;
	}

	@Override
	public String getKey() {
		return KEY;
	}

	@Override
	public String getName() {
		return NAME;
	}
	
}
