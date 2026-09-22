import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.view.ViewBox;

/** Headless SVG renderer; no application or desktop interaction required. */
public class RenderLogo
{
    public static void main(String[] args) throws Exception
    {
        var document = new SVGLoader().load(Path.of(args[0]).toUri().toURL());
        if (document == null)
            throw new IllegalArgumentException("Cannot load SVG: " + args[0]);
        int size = Integer.parseInt(args[2]);
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try
        {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            document.render(null, graphics, new ViewBox(size, size));
        }
        finally
        {
            graphics.dispose();
        }
        var output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
    }
}
