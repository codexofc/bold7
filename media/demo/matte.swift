// swift matte.swift <photo> <mask.png>: the mask of the photo's subject (the phone, and the hand
// that holds it), by Vision's foreground instance mask (macOS 14 or later). Run by prepare-assets.sh.
import CoreImage
import Foundation
import Vision

let args = CommandLine.arguments
guard args.count == 3, let input = CIImage(contentsOf: URL(fileURLWithPath: args[1])) else {
	FileHandle.standardError.write("usage: swift matte.swift <photo> <mask.png>\n".data(using: .utf8)!)
	exit(2)
}
let request = VNGenerateForegroundInstanceMaskRequest()
let handler = VNImageRequestHandler(ciImage: input)
try handler.perform([request])
guard let result = request.results?.first else {
	FileHandle.standardError.write("no subject in \(args[1])\n".data(using: .utf8)!)
	exit(1)
}
let mask = try result.generateScaledMaskForImage(forInstances: result.allInstances, from: handler)
try CIContext().writePNGRepresentation(
	of: CIImage(cvPixelBuffer: mask), to: URL(fileURLWithPath: args[2]), format: .L8, colorSpace: CGColorSpaceCreateDeviceGray())
