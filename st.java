import net.sf.geographiclib.Geodesic;
import net.sf.geographiclib.GeodesicData;
import net.sf.geographiclib.GeodesicLine;
import net.sf.geographiclib.PolygonArea;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.lang.Math;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

public class EnvironmentExtractor extends AbsExtractor {
    /*
    The EnvironmentExtractor tries to extract the location and time the event happened.
    */

    private static final int one_minute_in_s = 60;
    private static final int one_hour_in_s = one_minute_in_s * 60;
    private static final int one_day_in_s = one_hour_in_s * 24;
    private static final int two_days_in_s = one_day_in_s * 2;
    private static final int one_month_in_s = one_day_in_s * 30;

    // used for normalization of time
    private static final double time_norm_min = Math.log(one_minute_in_s);
    private static final double time_norm_max = Math.log(one_month_in_s * 12); // a year
    private static final double time_norm_delta = time_norm_max - time_norm_min;

    // used for normalization of area (in square meters)
    private static final double area_norm_min = Math.log(225); // roughly one small building
    private static final double area_norm_max = Math.log(530000000000.0); // mean size of all countries (CIA factbook)
    private static final double area_norm_delta = area_norm_max - area_norm_min;

   public EnvironmentExtractor(double[][] weights, int phrase_range_location, int time_range, String host, boolean skip_when,
                                boolean skip_where) {
        super(weights, phrase_range_location, time_range, host, skip_when, skip_where);
        this.weights = weights;
        this.geocoder = new Nominatim(domain, timeout);
        this.calendar = new pdt.Calendar();

        // date strings like 'monday' can denote dates in the future as well as in the past
        // in most cases an article describes an event in the past
        this.calendar.set(Calendar.DOW_PARSE_STYLE, -1);
        this.calendar.set(Calendar.CURRENT_DOW_PARSE_STYLE, true);

        this.time_delta = time_range;
        this.phrase_range_location = phrase_range_location;
        this.cache_nominatim = CacheManager.instance().get_cache("../examples/caches/Nominatim");
        this.skip_when = skip_when;
        this.skip_where = skip_where;
    }

    private void _evaluate_candidates(Document document) {
        if (!this.skip_where) {
            List<String> locations = this._evaluate_locations(document);
            List<String> locations_clean = this._filter_candidate_duplicates(locations);
            document.set_answer("where", locations_clean);
        }

        if (!this.skip_when) {
            List<String> dates = this._evaluate_timex_dates(document);
            List<String> locations_dates = this._filter_candidate_duplicates(dates);
            document.set_answer("when", locations_dates);
        }
    }

 public List<Map<String, Object>> _extract_timex_candidates(List<Map<String, Object>> tokens) {
    Map<String, List<Map<String, Object>>> timexDates = new HashMap<>();

    for (Map<String, Object> curToken : tokens) {
        if (curToken.containsKey("timex")) {
            Map<String, Object> timexObj = (Map<String, Object>) curToken.get("timex");
            String timexId = (String) timexObj.get("tid");

            if (timexDates.containsKey(timexId)) {
                List<Map<String, Object>> foundCandidateTokenList = timexDates.get(timexId);
                foundCandidateTokenList.add(curToken);
            } else {
                List<Map<String, Object>> foundCandidateTokenList = new ArrayList<>();
                foundCandidateTokenList.add(curToken);
                timexDates.put(timexId, foundCandidateTokenList);
            }
        }
    }

    List<Map<String, Object>> candidateList = new ArrayList<>();
    for (String timexId : timexDates.keySet()) {
        List<Map<String, Object>> timexTokenList = timexDates.get(timexId);
        Map<String, Object> candidate = new HashMap<>();
        candidate.put("tokens", timexTokenList);
        candidate.put("type", "TIMEX");
        candidateList.add(candidate);
    }

    return candidateList;
}
public void _extract_candidates(Document document) {
    List<Candidate> locations = new ArrayList<>();
    List<Candidate> timexCandidates = new ArrayList<>();

    List<List<Map<String, Object>>> tokens = document.getTokens();

    for (int i = 0; i < tokens.size(); i++) {
        List<Map<String, Object>> sentence = tokens.get(i);
        for (Candidate candidate : _extract_entities(sentence, Arrays.asList("LOCATION"), true,
                _phrase_range_location, "ner")) {

            List<Map<String, Object>> candidateTokens = candidate.getRaw();

            List<String> locationArray = new ArrayList<>();
            for (Map<String, Object> token : candidateTokens) {
                locationArray.add((String) token.get("originalText"));
            }
            String locationString = String.join(" ", locationArray);

            Object cachedLocation = _cache_nominatim.get(locationString);
            if (cachedLocation == null) {
                Location location = geocoder.geocode(locationString);
                if (location == null) {
                    _cache_nominatim.cache(locationString, -1);
                } else {
                    _cache_nominatim.cache(locationString, location);
                }
                cachedLocation = location;
            }
            if (cachedLocation != null && !(cachedLocation instanceof Integer)) {
                Location location = (Location) cachedLocation;
                Candidate ca = new Candidate();
                ca.setRaw(candidateTokens);
                ca.setSentenceIndex(i);
                ca.setCalculations("openstreetmap_nominatim", location);
                ca.setEnhancement("openstreetmap_nominatim", location.getRaw());
                locations.add(ca);
            }
        }

        List<Map<String, Object>> currentTimexCandidates = _extract_timex_candidates(sentence);
        for (Map<String, Object> timexCandidate : currentTimexCandidates) {
            Object timexDateValue = timexCandidate.get("value");
            if (timexDateValue != null && timexDateValue instanceof String) {
                String timexDateValueString = (String) timexDateValue;
                Timex timexObj = Timex.fromTimexText(timexDateValueString);
                if (timexObj != null) {
                    Candidate ca = new Candidate();
                    ca.setRaw((List<Map<String, Object>>) timexCandidate.get("tokens"));
                    ca.setSentenceIndex(i);
                    ca.setCalculations("timex", timexObj);
                    ca.setEnhancement("timex", timexObj.getJson());
                    timexCandidates.add(ca);
                }
            }
        }
    }

    document.setCandidates(getId() + "Locations", locations);
    document.setCandidates(getId() + "TimexDates", timexCandidates);
}

public List<Candidate> _evaluate_locations(Document document) {
    List<Candidate> rawLocations = new ArrayList<>();
    List<Candidate> uniqueLocations = new ArrayList<>();
    List<Candidate> rankedLocations = new ArrayList<>();
    double[] weights = weights[0];
    double weightsSum = 0;
    double maxArea = 1;

    List<Candidate> candidates = document.getCandidates(getId() + "Locatios");

    for (Candidate candidate : candidates) {
        List<Map<String, Object>> parts = candidate.getRaw();
        Location location = candidate.getCalculations("openstreetmap_nominatim");
        double[] bb = (double[]) location.getRaw().get("boundingbox");

        double area = (int) greatCircleDistance(bb[0], bb[2], bb[0], bb[3]) *
                (int) greatCircleDistance(bb[0], bb[2], bb[1], bb[2]);

        rawLocations.add(new Candidate(parts, location.getRaw().get("place_id"), location.getPoint(),
                bb, area, 0, 0, candidate, 0));

        maxArea = Math.max(maxArea, area);
    }

    rawLocations.sort((a, b) -> Integer.compare(Integer.parseInt(b.getCalculations("openstreetmap_nominatim")
            .getRaw().get("place_id").toString()), Integer.parseInt(a.getCalculations("openstreetmap_nominatim")
            .getRaw().get("place_id").toString())));

    for (int i = 0; i < rawLocations.size(); i++) {
        Candidate location = rawLocations.get(i);
        List<Integer> positions = new ArrayList<>();
        positions.add(location.getPosition());

        for (int j = i + 1; j < rawLocations.size(); j++) {
            Candidate alt = rawLocations.get(j);
            if (location.getCalculations("openstreetmap_nominatim").getRaw().get("place_id")
                    .equals(alt.getCalculations("openstreetmap_nominatim").getRaw().get("place_id"))) {
                positions.add(alt.getPosition());
            }
        }

        location.setPosition(Collections.min(positions));
        location.setFrequency(positions.size());
        uniqueLocations.add(location);
        i += positions.size() - 1;
    }

    uniqueLocations.sort((a, b) -> Double.compare(b.getArea(), a.getArea()));

    int maxFrequency = 1;
    int maxEntailment = 1;

    for (Candidate location : uniqueLocations) {
        for (int i = uniqueLocations.indexOf(location) + 1; i < rawLocations.size(); i++) {
            Candidate alt = rawLocations.get(i);
            double[] bbAlt = alt.getBoundingBox();
            double lat = location.getPoint()[0];
            double lon = location.getPoint()[1];
            if (bbAlt[0] >= lat && lat >= bbAlt[1] && bbAlt[2] >= lon && lon >= bbAlt[3]) {
                location.setEntailment(location.getEntailment() + 1);
            }
        }
        maxFrequency = Math.max(maxFrequency, location.getFrequency());
        maxEntailment = Math.max(maxEntailment, location.getEntailment());
    }

    for (Candidate location : uniqueLocations) {
        double score = weights[0] * (document.getLen() - location.getPosition()) / document.getLen();
        score += weights[1] * (location.getFrequency() / (double) maxFrequency);
        score += weights[2] * (location.getEntailment() / (double) maxEntailment);

        double normalizedArea = ((Math.log(location.getArea() + 1)) - EnvironmentExtractor.getAreaNormMin()) /
                EnvironmentExtractor.getAreaNormDelta;
        normalizedArea = Math.min(normalizedArea, 1);
        normalizedArea = Math.max(normalizedArea, 0);
        score += weights[3] * (1 - normalizedArea);

        if (score > 0) {
            score /= weightsSum;
        }

        Candidate ca = location.getCandidate();
        ca.setScore(score);
        rankedLocations.add(ca);
    }

    rankedLocations.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

    for (Candidate ranked : rankedLocations) {
        List<Map<String, Object>> rawList = ranked.getRaw();
        List<Map<String, Object>> parts = new ArrayList<>();
        for (Map<String, Object> raw : rawList) {
            parts.add(Map.of("nlpToken", raw, "pos", raw.get("pos")));
        }
        ranked.setParts(parts);
    }

    return rankedLocations;
}

}