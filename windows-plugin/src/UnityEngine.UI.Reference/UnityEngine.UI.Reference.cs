using UnityEngine;

// Compile-only API surface for the small subset of uGUI used by the overlay.
// This assembly is never shipped. At runtime BepInEx resolves UnityEngine.UI
// to the game's generated IL2CPP interop assembly.
namespace UnityEngine.UI;

public class GraphicRaycaster : MonoBehaviour
{
}

public class CanvasScaler : MonoBehaviour
{
    public enum ScaleMode
    {
        ConstantPixelSize,
        ScaleWithScreenSize,
        ConstantPhysicalSize,
    }

    public enum ScreenMatchMode
    {
        MatchWidthOrHeight,
        Expand,
        Shrink,
    }

    public ScaleMode uiScaleMode { get; set; }
    public Vector2 referenceResolution { get; set; }
    public ScreenMatchMode screenMatchMode { get; set; }
    public float matchWidthOrHeight { get; set; }
}

public class Image : MonoBehaviour
{
    public enum Type
    {
        Simple,
        Sliced,
        Tiled,
        Filled,
    }

    public Sprite sprite { get; set; } = null!;
    public Type type { get; set; }
    public Color color { get; set; }
    public bool raycastTarget { get; set; }
}

public class Text : MonoBehaviour
{
    public string text { get; set; } = string.Empty;
    public Font font { get; set; } = null!;
    public int fontSize { get; set; }
    public FontStyle fontStyle { get; set; }
    public Color color { get; set; }
    public TextAnchor alignment { get; set; }
    public HorizontalWrapMode horizontalOverflow { get; set; }
    public VerticalWrapMode verticalOverflow { get; set; }
    public bool raycastTarget { get; set; }

    public void SetVerticesDirty()
    {
    }

    public void SetLayoutDirty()
    {
    }
}
