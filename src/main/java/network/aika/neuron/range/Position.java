package network.aika.neuron.range;


import network.aika.Document;
import network.aika.Model;
import network.aika.Writable;
import network.aika.neuron.activation.Activation;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.SortedSet;
import java.util.TreeSet;

import static network.aika.neuron.range.Position.Operator.*;

public class Position {

    public static final Position MIN = new Position(null, Integer.MIN_VALUE);
    public static final Position MAX = new Position(null, Integer.MAX_VALUE);


    public SortedSet<Activation> beginActivations = new TreeSet<>();
    public SortedSet<Activation> endActivations = new TreeSet<>();


    public Document doc;
    public final int id;
    private Integer finalPosition;

    private long visited;


    public Position(Document doc) {
        this(doc, null);
    }


    public Position(Document doc, Integer pos) {
        this.doc = doc;
        this.id = doc != null ? doc.positionIdCounter++ : -1;
        finalPosition = pos;
    }


    public static int compare(Position a, Position b) {
        return a.compare(b);
    }

    public int compare(Position pos) {
        if(finalPosition != null && pos.finalPosition != null) {
            return Integer.compare(finalPosition, pos.finalPosition);
        }

        return 0; // TODO:
    }


    public String toString() {
        return finalPosition != null ? "" + finalPosition : "<" + id + ">";
    }



    private boolean lessThan(Position pos, long v) {
        if(visited == v) return false;
        visited = v;

        if(this == pos) return true;

        for(Activation act: beginActivations) {
            Position nextPos = act.range.end;

            if(lessThan(nextPos, v)) return true;
        }

        return false;
    }


    public Integer getDistance(Position pos) {
        if(finalPosition != null && pos.finalPosition != null) {
            return pos.finalPosition - finalPosition;
        }
        return null;
    }


    public Integer getFinalPosition() {
        return finalPosition;
    }


    public void setFinalPosition(int finalPos) {
        finalPosition = finalPos;
    }


    public void addBeginActivation(Activation act) {
        beginActivations.add(act);
    }

    public void addEndActivations(Activation act) {
        endActivations.add(act);
    }


    public enum Operator {
        EQUALS(0),
        LESS_THAN_EQUAL(1),
        GREATER_THAN_EQUAL(2),
        LESS_THAN(3),
        GREATER_THAN(4),
        NONE(5);

        Operator(int id) {
            this.id = (short) id;
        }

        int id;

        public static Operator getById(int id) {
            for(Operator o: Operator.values()) {
                if(o.id == id) return o;
            }
            return null;
        }


        public boolean isGreaterThanOrGreaterThanEqual() {
            return this == GREATER_THAN || this == GREATER_THAN_EQUAL;
        }


        public boolean isLessThanOrLessThanEqual() {
            return this == LESS_THAN || this == LESS_THAN_EQUAL;
        }


        public boolean includesEqual() {
            return this == EQUALS || this == GREATER_THAN_EQUAL || this == LESS_THAN_EQUAL;
        }


        public int getId() {
            return id;
        }


        public Operator invert() {
            switch(this) {
                case EQUALS:
                    return EQUALS;
                case LESS_THAN_EQUAL:
                    return GREATER_THAN_EQUAL;
                case GREATER_THAN_EQUAL:
                    return LESS_THAN_EQUAL;
                case LESS_THAN:
                    return GREATER_THAN;
                case GREATER_THAN:
                    return LESS_THAN;
                default:
                    return NONE;
            }
        }


        public boolean compare(Position a, Position b) {
            if(this == NONE) {
                return true;
            } else if(this == EQUALS) {
                return a == b;
            } else if(a.finalPosition != null && b.finalPosition != null) {
                switch(this) {
                    case LESS_THAN_EQUAL:
                        return a.finalPosition <= b.finalPosition;
                    case GREATER_THAN_EQUAL:
                        return a.finalPosition >= b.finalPosition;
                    case LESS_THAN:
                        return a.finalPosition < b.finalPosition;
                    case GREATER_THAN:
                        return a.finalPosition > b.finalPosition;
                    default:
                        return true;
                }
            } else if(a == b) {
                return this == EQUALS || this == LESS_THAN_EQUAL || this == GREATER_THAN_EQUAL;
            }

            return lessThan(b, a.doc.visitedCounter++) && this == LESS_THAN;
        }
    }

}
