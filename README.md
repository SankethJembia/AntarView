# Liver Model Assessor

Windows desktop viewer for the STL or OBJ surface from the liver reconstruction pipeline.

## Open a mesh

Launch `LiverModelAssessor.exe` from the packaged app folder and choose **Open mesh…**. A mesh can also be passed as the first argument. The [measurement block](src/test/resources/measurement-block.obj) is a sample with dimensions of 40 × 30 × 20 model units.

- **View.** Drag to orbit, middle-drag to pan, scroll to zoom, or use **Reset view**.
- **Slice.** Select X, Y, or Z and move the slider. The cross-section shows where the plane intersects the mesh. Enable **Clip 3D model** for a cutaway and use **Flip kept side** to inspect the other half.
- **Measure.** Select **Pick two points** and click the surface twice. The result is the straight-line distance between those points. **Clear** removes the markers.
- **Units.** The viewer starts in model units. Use **Set scale…** to enter millimetres per model unit if the exporter’s scale is known. Open a new mesh to start with its own scale.

The viewer handles surface geometry. MRI and liver-mask comparison and tumor annotations are later milestones in the [V2 plan](prototype%20V2.md).

## Build

Use a Windows x64 environment with JDK 21 and Maven. From this directory in PowerShell:

```powershell
mvn package
java -jar target/app/liver-model-assessor.jar
```

The Maven build runs the geometry tests and stages the Windows VTK runtime alongside the app JAR. The tests exercise OBJ import, ASCII and binary STL import, contour and clipping geometry on all three axes, and two-point picking on a known shape.

Build the Windows application image with JDK 21:

```powershell
jpackage --type app-image --name LiverModelAssessor --app-version 0.1.0 --input target/app --main-jar liver-model-assessor.jar --dest target/release --add-modules 'java.desktop,java.logging,java.management,jdk.unsupported'
```

Launch `target/release/LiverModelAssessor/LiverModelAssessor.exe`. Keep the whole app folder together so the launcher can find its runtime and dependencies.
