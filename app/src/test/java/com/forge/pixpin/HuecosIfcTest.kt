package com.forge.pixpin

import com.forge.pixpin.motor.Csg
import com.forge.pixpin.motor.LectorIfc
import com.forge.pixpin.motor.Malla3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** **Los huecos de puertas y ventanas, restados.** Ver [Csg] y [LectorIfc]. */
class HuecosIfcTest {

    private val identidad = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)

    private fun volumen(m: Malla3D, pieza: Malla3D.Pieza): Double =
        Csg.volumen(Csg.solido(m.vertices, m.triangulos, m.colores, pieza.desde, pieza.hasta))

    @Test
    fun `una caja menos otra que la atraviesa pierde justo lo comun`() {
        val muro = Csg.caja(identidad, 0.0, 0.0, 0.0, 4.0, 0.2, 3.0, 1)
        val hueco = Csg.caja(identidad, 1.5, -0.5, 0.0, 2.5, 0.7, 2.0, 2)
        assertEquals(2.4, Csg.volumen(muro), 1e-9)
        val r = Csg.restar(muro, hueco, 1)
        assertEquals(2.4 - 0.4, Csg.volumen(r), 1e-6)
        assertTrue(r.all { it.color == 1 })
        assertEquals(0.4, Csg.volumen(Csg.intersecar(muro, hueco)), 1e-6)
    }

    @Test
    fun `restar lo que no se toca no cambia nada`() {
        val a = Csg.caja(identidad, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1)
        val b = Csg.caja(identidad, 5.0, 5.0, 5.0, 6.0, 6.0, 6.0, 1)
        assertEquals(1.0, Csg.volumen(Csg.restar(a, b)), 1e-12)
    }

    @Test
    fun `un prisma en L tiene el area de la L por su alto, venga en el sentido que venga`() {
        val l = doubleArrayOf(0.0, 0.0, 4.0, 0.0, 4.0, 1.0, 1.0, 1.0, 1.0, 4.0, 0.0, 4.0)
        assertEquals(14.0, Csg.volumen(Csg.prisma(identidad, l, 0.0, 2.0, 1)), 1e-9)
        val alReves = DoubleArray(l.size).also { for (i in 0 until l.size / 2) { val k = l.size / 2 - 1 - i; it[i * 2] = l[k * 2]; it[i * 2 + 1] = l[k * 2 + 1] } }
        assertEquals(14.0, Csg.volumen(Csg.prisma(identidad, alReves, 0.0, 2.0, 1)), 1e-9)
    }

    @Test
    fun `un muro de IFC sale con su hueco y otro recortado por un plano`() {
        val ifc = """
ISO-10303-21;
HEADER;
FILE_DESCRIPTION((''),'2;1');
ENDSEC;
DATA;
#1=IFCDIRECTION((0.,0.,1.));
#2=IFCDIRECTION((1.,0.,0.));
#3=IFCCARTESIANPOINT((0.,0.,0.));
#4=IFCAXIS2PLACEMENT3D(#3,#1,#2);
#5=IFCLOCALPLACEMENT($,#4);
#6=IFCCARTESIANPOINT((2.,0.1));
#7=IFCAXIS2PLACEMENT2D(#6,$);
#8=IFCRECTANGLEPROFILEDEF(.AREA.,$,#7,4.,0.2);
#9=IFCEXTRUDEDAREASOLID(#8,#4,#1,3.);
#10=IFCSHAPEREPRESENTATION($,'Body','SweptSolid',(#9));
#11=IFCPRODUCTDEFINITIONSHAPE($,$,(#10));
#12=IFCWALL('g1',$,'Muro con ventana',$,$,#5,#11,$,$);
#13=IFCCARTESIANPOINT((2.,0.1));
#14=IFCAXIS2PLACEMENT2D(#13,$);
#15=IFCRECTANGLEPROFILEDEF(.AREA.,$,#14,1.,0.6);
#30=IFCCARTESIANPOINT((0.,0.,0.5));
#31=IFCAXIS2PLACEMENT3D(#30,#1,#2);
#16=IFCEXTRUDEDAREASOLID(#15,#31,#1,1.5);
#17=IFCSHAPEREPRESENTATION($,'Body','SweptSolid',(#16));
#18=IFCPRODUCTDEFINITIONSHAPE($,$,(#17));
#19=IFCOPENINGELEMENT('g2',$,'Hueco',$,$,#5,#18,$,$);
#20=IFCRELVOIDSELEMENT('g3',$,$,$,#12,#19);
#40=IFCCARTESIANPOINT((0.,0.,2.5));
#41=IFCAXIS2PLACEMENT3D(#40,#1,#2);
#42=IFCPLANE(#41);
#43=IFCHALFSPACESOLID(#42,.F.);
#44=IFCBOOLEANCLIPPINGRESULT(.DIFFERENCE.,#9,#43);
#45=IFCSHAPEREPRESENTATION($,'Body','Clipping',(#44));
#46=IFCPRODUCTDEFINITIONSHAPE($,$,(#45));
#47=IFCWALL('g4',$,'Muro bajo el tejado',$,$,#5,#46,$,$);
ENDSEC;
END-ISO-10303-21;
""".trimStart()
        val f = File.createTempFile("huecos", ".ifc").apply { writeText(ifc); deleteOnExit() }
        val m = LectorIfc.leer(f)
        val conVentana = m.piezas.first { it.nombre == "Muro con ventana" }
        val bajo = m.piezas.first { it.nombre == "Muro bajo el tejado" }
        // 4 × 0,2 × 3 menos la ventana, 1 × 0,2 × 1,5.
        assertEquals(2.4 - 0.3, volumen(m, conVentana), 1e-4)
        // Recortado a 2,5 m de alto.
        assertEquals(4 * 0.2 * 2.5, volumen(m, bajo), 1e-4)
        // El hueco no se pinta como si fuera una pieza.
        assertTrue(m.piezas.none { it.nombre == "Hueco" })
    }
}
