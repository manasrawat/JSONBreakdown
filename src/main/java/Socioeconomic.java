public class Socioeconomic {
    private double social, economic;
    private int socialTotal, economicTotal;

    public Socioeconomic(double social, double economic, int socialTotal, int economicTotal) {
        this.social = social;
        this.economic = economic;
        this.socialTotal = socialTotal;
        this.economicTotal = economicTotal;
    }


    public double getSoc() {
        return social;
    }

    public void setSoc(double social) {
        this.social = social;
    }

    public double getEcon() {
        return economic;
    }

    public void setEcon(double economic) {
        this.economic = economic;
    }

    public int getSocTotal() {
        return socialTotal;
    }

    public void setSocTotal(int socialTotal) {
        this.socialTotal = socialTotal;
    }

    public int getEconTotal() {
        return economicTotal;
    }

    public void setEconTotal(int economicTotal) {
        this.economicTotal = economicTotal;
    }

}
