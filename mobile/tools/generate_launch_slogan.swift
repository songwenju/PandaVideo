import AppKit
import CoreText

// Android system splash screens accept drawables rather than localized text views.
// Keep the historical slogan as text here so the light/dark artwork is reproducible.
let slogan = "影视 · 随心看"
let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
    .deletingLastPathComponent().appendingPathComponent("app/src/main/res")
let scale: CGFloat = 4
let width = 200 * Int(scale)
let height = 80 * Int(scale)

for (folder, gray) in [("drawable-xxxhdpi", CGFloat(0.46)),
                        ("drawable-night-xxxhdpi", CGFloat(0.68))] {
    let directory = root.appendingPathComponent(folder)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
                            bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    let attributes: [NSAttributedString.Key: Any] = [
        .font: NSFont.systemFont(ofSize: 16 * scale, weight: .medium),
        .foregroundColor: NSColor(white: gray, alpha: 1),
    ]
    let line = CTLineCreateWithAttributedString(NSAttributedString(string: slogan, attributes: attributes))
    var ascent: CGFloat = 0
    var descent: CGFloat = 0
    let textWidth = CTLineGetTypographicBounds(line, &ascent, &descent, nil)
    context.textPosition = CGPoint(x: (CGFloat(width) - textWidth) / 2,
                                   y: (CGFloat(height) - ascent - descent) / 2 + descent)
    CTLineDraw(line, context)
    let bitmap = NSBitmapImageRep(cgImage: context.makeImage()!)
    try bitmap.representation(using: .png, properties: [:])!
        .write(to: directory.appendingPathComponent("launch_slogan.png"))
}
