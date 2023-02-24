package org.geotools.gml3.bindings;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.geotools.gml3.ArcParameters;
import org.geotools.gml3.Circle;
import org.geotools.gml3.GML;
import org.geotools.xml.*;

import com.vividsolutions.jts.algorithm.CGAlgorithms;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.CoordinateSequenceFactory;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.LineString;

import javax.xml.namespace.QName;

/**
 * Binding object for the type http://www.opengis.net/gml:ArcStringType.
 * <p>
 * 
 * <pre>
 *  <code>
 *  &lt;complexType name="ArcStringType"&gt;
 *      &lt;annotation&gt;
 *          &lt;documentation&gt;An ArcString is a curve segment that uses three-point circular arc interpolation.&lt;/documentation&gt;
 *      &lt;/annotation&gt;
 *      &lt;complexContent&gt;
 *          &lt;extension base="gml:AbstractCurveSegmentType"&gt;
 *              &lt;sequence&gt;
 *                  &lt;choice&gt;
 *                      &lt;annotation&gt;
 *                          &lt;documentation&gt;GML supports two different ways to specify the control points of a curve segment.
 *  1. A sequence of "pos" (DirectPositionType) or "pointProperty" (PointPropertyType) elements. "pos" elements are control points that are only part of this curve segment, "pointProperty" elements contain a point that may be referenced from other geometry elements or reference another point defined outside of this curve segment (reuse of existing points).
 *  2. The "posList" element allows for a compact way to specifiy the coordinates of the control points, if all control points are in the same coordinate reference systems and belong to this curve segment only. The number of direct positions in the list must be at least three.&lt;/documentation&gt;
 *                      &lt;/annotation&gt;
 *                      &lt;choice maxOccurs="unbounded" minOccurs="3"&gt;
 *                          &lt;element ref="gml:pos"/&gt;
 *                          &lt;element ref="gml:pointProperty"/&gt;
 *                          &lt;element ref="gml:pointRep"&gt;
 *                              &lt;annotation&gt;
 *                                  &lt;documentation&gt;Deprecated with GML version 3.1.0. Use "pointProperty" instead. Included for backwards compatibility with GML 3.0.0.&lt;/documentation&gt;
 *                              &lt;/annotation&gt;
 *                          &lt;/element&gt;
 *                      &lt;/choice&gt;
 *                      &lt;element ref="gml:posList"/&gt;
 *                      &lt;element ref="gml:coordinates"&gt;
 *                          &lt;annotation&gt;
 *                              &lt;documentation&gt;Deprecated with GML version 3.1.0. Use "posList" instead.&lt;/documentation&gt;
 *                          &lt;/annotation&gt;
 *                      &lt;/element&gt;
 *                  &lt;/choice&gt;
 *              &lt;/sequence&gt;
 *              &lt;attribute fixed="circularArc3Points" name="interpolation" type="gml:CurveInterpolationType"&gt;
 *                  &lt;annotation&gt;
 *                      &lt;documentation&gt;The attribute "interpolation" specifies the curve interpolation mechanism used for this segment. This mechanism
 *  uses the control points and control parameters to determine the position of this curve segment. For an ArcString the interpolation is fixed as "circularArc3Points".&lt;/documentation&gt;
 *                  &lt;/annotation&gt;
 *              &lt;/attribute&gt;
 *              &lt;attribute name="numArc" type="integer" use="optional"&gt;
 *                  &lt;annotation&gt;
 *                      &lt;documentation&gt;The number of arcs in the arc string can be explicitly stated in this attribute. The number of control points in the arc string must be 2 * numArc + 1.&lt;/documentation&gt;
 *                  &lt;/annotation&gt;
 *              &lt;/attribute&gt;
 *          &lt;/extension&gt;
 *      &lt;/complexContent&gt;
 *  &lt;/complexType&gt; 
 * 	
 *   </code>
 * </pre>
 * 
 * </p>
 * 
 * @generated
 */
public class ArcStringTypeBinding extends AbstractComplexBinding {

    GeometryFactory gFactory;
    CoordinateSequenceFactory csFactory;
    ArcParameters arcParameters;

    public ArcStringTypeBinding(GeometryFactory gFactory, CoordinateSequenceFactory csFactory, ArcParameters arcParameters) {
        this.gFactory = gFactory;
        this.csFactory = csFactory;
        this.arcParameters = arcParameters;
    }

    /**
     * @generated
     */
    public QName getTarget() {
        return GML.ArcStringType;
    }

    @Override
    public int getExecutionMode() {
        return OVERRIDE;
    }

    /**
     * <!-- begin-user-doc --> <!-- end-user-doc -->
     * 
     * @generated modifiable
     */
    public Class getType() {
        return LineString.class;
    }
    
    /**
     * <!-- begin-user-doc --> <!-- end-user-doc -->
     * 
     * @generated modifiable
     */
    public Object parse(ElementInstance instance, Node node, Object value)
            throws Exception {
    
        LineString arcLineString = GML3ParsingUtils.lineString(node, gFactory, csFactory);
        
        Coordinate[] arcCoordinates = arcLineString.getCoordinates();
        if (arcCoordinates.length < 3) {
            // maybe log this instead and return null
            throw new RuntimeException("Number of coordinates in an arc string must be at least 3, " 
                    + arcCoordinates.length + " were specified: " + arcLineString);
        }

        Coordinate c1 = arcCoordinates[0];
        Coordinate c2 = arcCoordinates[arcCoordinates.length/2];
        Coordinate c3 = arcCoordinates[arcCoordinates.length-1];

        // determine whether we need to reverse our input.
        boolean mustReverse = laidOutClockwise(c1, c2, c3);

        if (mustReverse) {
            // swap coords 1 and 3
            Coordinate cTemp = c1;
            c1 = c3;
            c3 = cTemp;
        }

        Circle circle = new Circle(c1, c2, c3);
        double tolerance = arcParameters.getLinearizationTolerance().getTolerance(circle);
        Coordinate[] resultCoordinates = circle.linearizeArc(c1, c2, c3, tolerance);

        if (mustReverse) {
            // reverse back
            List<Coordinate> reversingCoordinates = Arrays.asList(resultCoordinates);
            Collections.reverse(reversingCoordinates);
            resultCoordinates = (Coordinate[])
                    reversingCoordinates.toArray(new Coordinate[reversingCoordinates.size()]);
        }

        LineString resultLineString = gFactory.createLineString(resultCoordinates);

        return resultLineString;
    }

    /**
     * Returns whether the input coordinates are laid out clockwise on their corresponding circle.
     * Only works correctly if the Euclidean distance between c1 and c2 is equal to the Euclidean distance between c2 and c3.
     * @param c1
     * @param c2
     * @param c3
     * @return true if input coordinates are laid out clockwise on their corresponding circle. false otherwise.
     */
    protected boolean laidOutClockwise(Coordinate c1, Coordinate c2, Coordinate c3) {
        return CGAlgorithms.computeOrientation(c1, c2, c3) == CGAlgorithms.CLOCKWISE;
    }

}