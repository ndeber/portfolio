package name.abuchen.portfolio.pdfbox3;

import java.io.File;
import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.osgi.framework.FrameworkUtil;

public class PDFBox3Adapter
{
    public String convertToText(File file) throws IOException
    {
        try (PDDocument document = Loader.loadPDF(file))
        {
            PDFTextStripper textStripper = new PDFTextStripper();
            textStripper.setSortByPosition(true);
            return textStripper.getText(document);
        }
    }

    public String getPDFBoxVersion()
    {
        return FrameworkUtil.getBundle(PDDocument.class).getVersion().toString();
    }

    /** Extracts a column without interleaving the adjacent comparison tables. */
    public String convertToText(File file, double left, double right) throws IOException
    {
        if (!(left >= 0 && left < right && right <= 1))
            throw new IllegalArgumentException("Invalid PDF column bounds"); //$NON-NLS-1$
        try (PDDocument document = Loader.loadPDF(file))
        {
            var text = new StringBuilder();
            for (var page : document.getPages())
            {
                if (page.getRotation() != 0)
                    throw new IOException("Rotated PDF columns are not supported"); //$NON-NLS-1$
                var box = page.getCropBox();
                var stripper = new PDFTextStripperByArea();
                stripper.setSortByPosition(true);
                stripper.addRegion("column", new java.awt.geom.Rectangle2D.Double(left * box.getWidth(), 0, //$NON-NLS-1$
                                (right - left) * box.getWidth(), box.getHeight()));
                stripper.extractRegions(page);
                text.append(stripper.getTextForRegion("column")).append('\n'); //$NON-NLS-1$
            }
            return text.toString();
        }
    }
}
