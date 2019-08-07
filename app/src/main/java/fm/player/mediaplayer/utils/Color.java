// jaad-0.8.4.jar references java.awt.Color which doesn't exist on Android
//
// I've edited VideoMediaHeaderBox.java to refer to fm.player.mediaplayer.utils.Color instead and 
// then re-compiled it to .class then replaced the VideoMediaHeaderBox.class file inside
// jaad-0.8.4.jar

package fm.player.mediaplayer.utils;

public class Color
{
	public Color(int a, int b, int c)
	{
	}
}
