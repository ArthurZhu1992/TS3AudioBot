package pub.longyi.ts3audiobot.search;

import pub.longyi.ts3audiobot.search.SearchModels.SearchPage;

public interface SearchDetailProvider {
    SearchPage searchDetail(SearchProvider.SearchRequest request);
}
