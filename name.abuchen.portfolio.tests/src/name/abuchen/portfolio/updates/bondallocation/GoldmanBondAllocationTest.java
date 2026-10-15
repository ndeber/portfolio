package name.abuchen.portfolio.updates.bondallocation;

import static org.junit.Assert.*;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;

import org.junit.Test;

import name.abuchen.portfolio.updates.bondallocation.BondComposition.Family;
import name.abuchen.portfolio.updates.bondallocation.GoldmanBondAllocation.Document;

public class GoldmanBondAllocationTest
{
    private static final Document DOCUMENT = new Document(LocalDate.of(2026, 8, 31), "https://am.gs.com/public-assets/documents/report");
    private static final String TEXT = "Goldman Sachs Global Credit Portfolio (Hedged) LU0234688595 "
                    + "All performance and holdings data as at 31-Aug-26.";
    // PDFBox column order, verified against the August 2026 report. Each second
    // bar belongs to the benchmark and must never become a portfolio allocation.
    private static final String RATINGS = """
                    Credit Allocation (%)
                    3.2
                    AAA
                    1.2
                    7.5
                    AA
                    15.9
                    28.2
                    A
                    46.2
                    54.7
                    BBB
                    36
                    0.1
                    BB
                    0
                    0
                    B
                    0
                    0
                    NR
                    0.8
                    4.6
                    Cash
                    0
                    1.7
                    Derivatives
                    0
                    −100 −60 −20 20 60 100
                    Goldman Sachs Bloomberg Global Agg:
                    """;
    private static final String REGIONS = """
                    Sector Allocation (%)
                    86.8% Corporate - Inv.Grade
                    Geographical Bond Allocation (%)
                    Goldman Sachs Global Credit Portfolio (Hedged)
                    68.1% US
                    23.9% Eurozone
                    6.3% UK
                    0.0% Japan
                    0.0% Australia
                    0.0% Sweden
                    -0.1% Canada
                    1.7% Other
                    2

                    Top 10 Corporate Issuers
                    """;

    private String document(String isin, String type, String date, String id)
    {
        return "{\"LexFields\":[{\"name\":\"isin_s\",\"values\":[\"" + isin
                        + "\"]},{\"name\":\"documentType_s\",\"values\":[\"" + type
                        + "\"]},{\"name\":\"pvNumber_s\",\"values\":[\"PV100313\"]},"
                        + "{\"name\":\"displayDateTime_t\",\"values\":[\"" + date + "T00:00:00.000Z\"]},"
                        + "{\"name\":\"documentId_s\",\"values\":[\"" + id + "\"]}]}";
    }

    @Test public void selectsExactShareClassMonthlyReportBeforeReferenceDate() throws Exception
    {
        String json = "[" + document(GoldmanBondAllocation.ISIN, "exAnteCostsAndCharges", "2026-09-30", "fees") + ","
                        + document("LU0390464153", "monthlyFundUpdate", "2026-08-31", "wrong-share-class") + ","
                        + document(GoldmanBondAllocation.ISIN, "monthlyFundUpdate", "2026-07-31", "july") + ","
                        + document(GoldmanBondAllocation.ISIN, "monthlyFundUpdate", "2026-09-30", "future") + ","
                        + document(GoldmanBondAllocation.ISIN, "monthlyFundUpdate", "2026-08-31", "august") + "]";
        assertEquals(new Document(LocalDate.of(2026, 8, 31), "https://am.gs.com/public-assets/documents/august"),
                        GoldmanBondAllocation.latestDocument(json, LocalDate.of(2026, 9, 1)));
    }

    @Test public void missingOrAmbiguousDocumentsFailWithoutSubstitutingAnotherShareClass()
    {
        String other = "[" + document("LU0390464153", "monthlyFundUpdate", "2026-08-31", "other") + "]";
        assertThrows(IOException.class, () -> GoldmanBondAllocation.latestDocument(other, LocalDate.of(2026, 10, 15)));
        String ambiguous = "[" + document(GoldmanBondAllocation.ISIN, "monthlyFundUpdate", "2026-08-31", "one") + ","
                        + document(GoldmanBondAllocation.ISIN, "monthlyFundUpdate", "2026-08-31", "two") + "]";
        assertThrows(IOException.class, () -> GoldmanBondAllocation.latestDocument(ambiguous, LocalDate.of(2026, 10, 15)));
    }

    @Test public void ratingsUsePortfolioBarsAndKeepDerivativesUnrated() throws Exception
    {
        var result = GoldmanBondAllocation.parse(TEXT, RATINGS, Family.RATING, DOCUMENT);
        assertEquals(Map.of("AAA", 320, "AA", 750, "A", 2820, "BBB", 5470, "BB", 10, "Cash", 460, "Non noté", 170), result.weights());
        assertEquals(DOCUMENT.date(), result.date());
        assertEquals(DOCUMENT.url(), result.source());
        assertTrue(result.note().contains("dérivés"));
    }

    @Test public void regionsDoNotInventEurozoneCountriesOrNegativeAssignments() throws Exception
    {
        var result = GoldmanBondAllocation.parse(TEXT, REGIONS, Family.REGIONS, DOCUMENT);
        assertEquals(Map.of("États-Unis", 6810, "Europe", 2390, "Grande-Bretagne", 630, "Autres", 170), result.weights());
        assertFalse(result.weights().containsKey("Canada"));
        assertTrue(result.note().contains("positions négatives exclues"));
    }

    @Test public void wrongShareClassAndMismatchedOrAbsentPublicationDateAreRejected()
    {
        assertThrows(IOException.class, () -> GoldmanBondAllocation.parse(TEXT.replace(GoldmanBondAllocation.ISIN, "LU0390464153"), RATINGS, Family.RATING, DOCUMENT));
        assertThrows(IOException.class, () -> GoldmanBondAllocation.parse(TEXT.replace("31-Aug-26", "31-Jul-26"), RATINGS, Family.RATING, DOCUMENT));
        assertThrows(IOException.class, () -> GoldmanBondAllocation.parse("LU0234688595", RATINGS, Family.RATING, DOCUMENT));
    }

    @Test public void missingRowsAndTruncatedOrInconsistentTablesAreRejected()
    {
        assertThrows(IOException.class, () -> GoldmanBondAllocation.parse(TEXT, RATINGS.replace("7.5\nAA\n15.9\n", ""), Family.RATING, DOCUMENT));
        assertThrows(IOException.class, () -> GoldmanBondAllocation.parse(TEXT, RATINGS.replace("54.7", "44.7"), Family.RATING, DOCUMENT));
        assertThrows(IOException.class, () -> GoldmanBondAllocation.parse(TEXT, RATINGS.replace("Goldman Sachs", "missing"), Family.RATING, DOCUMENT));
        assertThrows(IOException.class, () -> GoldmanBondAllocation.parse(TEXT, REGIONS.replace("23.9% Eurozone\n", ""), Family.REGIONS, DOCUMENT));
        assertThrows(IOException.class, () -> GoldmanBondAllocation.parse(TEXT, REGIONS + REGIONS, Family.REGIONS, DOCUMENT));
    }

    @Test public void durationAndBroadCorporateSectorNeverFabricateMissingDistributions()
    {
        assertThrows(IOException.class, () -> GoldmanBondAllocation.parse(TEXT + " Current Duration 5.54", RATINGS, Family.MATURITY, DOCUMENT));
        assertThrows(IOException.class, () -> GoldmanBondAllocation.parse(TEXT + " 86.8% Corporate", RATINGS, Family.ISSUER, DOCUMENT));
    }
}
