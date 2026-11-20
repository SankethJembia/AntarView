# Liver Model Assessor

Windows desktop viewer for inspecting a reconstructed liver mesh and comparing it with a liver mask on the source MRI.

## Open a mesh

Launch `LiverModelAssessor.exe` from the packaged app folder and choose **Open mesh…**. A mesh can also be passed as the first argument. The [measurement block](src/test/resources/measurement-block.obj) is a sample with dimensions of 40 × 30 × 20 model units.

- **View.** Drag to orbit, middle-drag to pan, scroll to zoom, or use **Reset view**.
- **Slice.** Select X, Y, or Z and move the slider. The cross-section shows where the plane intersects the mesh. Enable **Clip 3D model** for a cutaway and use **Flip kept side** to inspect the other half.
- **Measure.** Select **Pick two points** and click the surface twice. The result is the straight-line distance between those points. **Clear** removes the markers.
- **Units.** The viewer starts in model units. Use **Set scale…** to enter millimetres per model unit if the exporter’s scale is known. Open a new mesh to start with its own scale.

## Open an MRI case

1. Choose **Open MRI case…** and browse for the generated STL or OBJ, source MRI, and matching liver mask. The MRI and mask must be scalar 3D `.nii` or `.nii.gz` files with coded spatial geometry in the same NIfTI frame. The mask uses zero for background and positive values for liver. Select **Use mm if NIfTI spatial units are unspecified** only if you have confirmed the files use millimetres.
2. For **Mesh alignment**, keep **Unknown mesh coordinates** to inspect the MRI and mask while using the standalone mesh viewer. If the exporter already writes coordinates in the MRI's RAS+ millimetre frame, select that option. For other known coordinate systems, choose a text file containing the affine from mesh coordinates to the loaded MRI's RAS+ millimetre frame. Enter four rows of four whitespace-separated numbers with a final row of `0 0 0 1`. Include any scale, orientation, and offset applied by the exporter.
3. Scroll through the sagittal, coronal, and axial views using their sliders. Click a slice to move the shared crosshair. With a mesh transform supplied, the gold mask outline and dashed teal mesh outline share the same slice geometry. Click the 3D mesh to move the crosshair to that surface point.
4. Review alignment in all three planes, then choose **Confirm and compare**. The app reports the median and 95th-percentile distance in each direction, from each source mesh vertex to the opposite surface. These are unsigned distances in millimetres. The two directions may differ with mesh sampling density.

Tumor annotation and counting are later milestones in the [V2 plan](prototype%20V2.md).

## Build

Use a Windows x64 environment with JDK 21 and Maven. From this directory in PowerShell:

```powershell
mvn package
java -jar target/app/liver-model-assessor.jar
```

The Maven build stages the Windows VTK runtime alongside the app JAR. Tests cover mesh operations, NIfTI spatial orientation and units, linked contour geometry, and surface distances on synthetic cases.

Build the Windows application image with JDK 21:

```powershell
jpackage --type app-image --name LiverModelAssessor --app-version 0.2.0 --input target/app --main-jar liver-model-assessor.jar --dest target/mri-release --add-modules 'java.desktop,java.logging,java.management,jdk.unsupported'
```

Launch `target/mri-release/LiverModelAssessor/LiverModelAssessor.exe`. Keep the whole app folder together so the launcher can find its runtime and dependencies.
