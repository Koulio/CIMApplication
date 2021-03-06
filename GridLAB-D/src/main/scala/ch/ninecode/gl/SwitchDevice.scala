package ch.ninecode.gl

import ch.ninecode.model._

class SwitchDevice (val one_phase: Boolean) extends Serializable
{
    def emit (edge: PreEdge, switch: Switch, fuse: Boolean = false): String =
    {
        val status = if (switch.normalOpen) "OPEN" else "CLOSED"
        var current = switch.ratedCurrent
        if (current <= 0) current = 9999.0 // ensure it doesn't trip
        val details = if (fuse)
            "            mean_replacement_time 3600.0;\n" + // sometimes: WARNING  [INIT] : Fuse:SIG8494 has a negative or 0 mean replacement time - defaulting to 1 hour
            "            current_limit " + current + "A;\n"
        else
            "            status \"" + status + "\";\n"
        val ret = if (one_phase)
            "        object " + (if (fuse) "fuse" else "switch") + "\n" +
            "        {\n" +
            "            name \"" + edge.id_equ + "\";\n" +
            "            phases AN;\n" +
            "            from \"" + edge.id_cn_1 + "\";\n" +
            "            to \"" + edge.id_cn_2 + "\";\n" +
            details +
            "        };\n"
        else
            "        object " + (if (fuse) "fuse" else "switch") + "\n" +
            "        {\n" +
            "            name \"" + edge.id_equ + "\";\n" +
            "            phases ABCN;\n" +
            "            from \"" + edge.id_cn_1 + "\";\n" +
            "            to \"" + edge.id_cn_2 + "\";\n" +
            details +
            "        };\n"
        return (ret)
    }
}