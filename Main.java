public class Main {
    public static void main(String[] args) {
        TargetMotion target = new TargetMotion(98.0, 50.0, 3.0, 4.0, 100.0, 100.0);
        target.update(1.0);
        double x = target.getX();
        double y = target.getY();
        System.out.println("Target location: (" + x + ", " + y + ")");

        target.update(1.0);
        x = target.getX();
        y = target.getY();
        System.out.println("Target location: (" + x + ", " + y + ")");
    }
}
